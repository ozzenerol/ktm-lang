/* Tracking allocator for JIT'd ktm. Disabled by default on the JIT,
 * enabled by default with the repl.
 *
 * MAlloc/Free/CAlloc/ReAlloc normally live in the libktm dylib, whose
 * INTERNAL libc calls never pass the JIT resolver - so interposition
 * happens at the ktm entry points. The wrappers ARE the
 * implementation rather than forwarding to the dylib: that keeps them
 * independent of the installed libktm (a stale or missing build for
 * the running arch would otherwise silently disable tracking). The
 * layout is just a u64 size header at [p-8] (memory.ktm), so MSize and
 * dylib-internal frees interoperate with blocks made here, and vice
 * versa. The tracked pointer is the payload - exactly what ktm code
 * holds. */

#if defined(__linux__)
#ifndef _GNU_SOURCE
#define _GNU_SOURCE /* dladdr */
#endif
#include <dlfcn.h>
#include <malloc.h> /* malloc_usable_size */
#endif
#include <dlfcn.h>
#include <pthread.h>
#include <setjmp.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>
#if defined(__APPLE__)
#include <mach-o/dyld.h>
#include <mach-o/loader.h>
#include <mach/vm_prot.h>
#else
#include <link.h>
#endif
#if defined(__linux__)
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#endif
#if defined(__APPLE__)
#include <malloc/malloc.h> /* malloc_zone_from_ptr */
#endif

#include "ast.h"
#include "containers.h"
#include "jit-common.h"
#include "memsafe.h"

/* Return addresses kept per allocation and free: enough to walk out of
 * the stdlib to the caller's own line */
#define MEMSAFE_SITES 12

typedef struct MemsafeEntry {
    void *ptr;
    u64 size;
    int alloc_round;
    int free_round;
    /* Raw return addresses into JIT code; symbolised lazily so the
     * alloc hot path never scans the symbol table. */
    uintptr_t alloc_site[MEMSAFE_SITES];
    uintptr_t free_site[MEMSAFE_SITES];
} MemsafeEntry;

static KtmJit *memsafe_jit = NULL;
static Map *memsafe_live = NULL; /* (u64)ptr -> MemsafeEntry* */
static int memsafe_round = 0;    /* attribution tag; <= 0 omitted */

/* Recently-freed ring: bounds how long a double free stays
 * classifiable. Beyond it the pointer falls through to the zone
 * check, i.e. untracked behaviour. The ring is also a quarantine: a
 * block is only handed back to libc when it drops off the ring, so
 * nothing (least of all an untracked libktm-internal MAlloc) can
 * reuse an address the ring still holds, and a ring hit is always a
 * real double free. */
#define MEMSAFE_RING 256
static MemsafeEntry memsafe_ring[MEMSAFE_RING];
static int memsafe_ring_next = 0;

#if defined(__linux__)
/* [lo, hi) of the main thread's stack, read once at init. */
static uintptr_t memsafe_stack_lo = 0;
static uintptr_t memsafe_stack_hi = 0;
#endif

/* The wrappers are called from any thread: the live map and the ring
 * are shared, so every entry point holds this. Recursive, as ReAlloc
 * goes through MAlloc and Free. */
static pthread_mutex_t memsafe_lock;
static int memsafe_lock_ready = 0;

static void memsafeLock(void) {
    if (memsafe_lock_ready) pthread_mutex_lock(&memsafe_lock);
}

static void memsafeUnlock(void) {
    if (memsafe_lock_ready) pthread_mutex_unlock(&memsafe_lock);
}

/* [lo, hi) of the calling thread's stack, looked up once per thread;
 * both 0 if the system won't say. */
static __thread uintptr_t memsafe_tstack_lo = 0;
static __thread uintptr_t memsafe_tstack_hi = 0;
static __thread int memsafe_tstack_known = 0;

static void memsafeThreadStack(uintptr_t *lo, uintptr_t *hi) {
    if (!memsafe_tstack_known) {
        memsafe_tstack_known = 1;
#if defined(__APPLE__)
        pthread_t self = pthread_self();
        uintptr_t top = (uintptr_t)pthread_get_stackaddr_np(self);
        size_t size = pthread_get_stacksize_np(self);
        if (top && size) {
            memsafe_tstack_hi = top;
            memsafe_tstack_lo = top - size;
        }
#else
        pthread_attr_t attr;
        if (pthread_getattr_np(pthread_self(), &attr) == 0) {
            void *addr = NULL;
            size_t size = 0;
            if (pthread_attr_getstack(&attr, &addr, &size) == 0 && addr) {
                memsafe_tstack_lo = (uintptr_t)addr;
                memsafe_tstack_hi = (uintptr_t)addr + size;
            }
            pthread_attr_destroy(&attr);
        }
#endif
    }
    *lo = memsafe_tstack_lo;
    *hi = memsafe_tstack_hi;
}

void memsafeSetRound(int round) {
    memsafe_round = round;
}

#if defined(__linux__)
/* libktm's symbols carry no size, so glibc's dladdr only names an exact
 * start address: read the library's .dynsym once and take the nearest
 * symbol at or below the address instead. */
static const Elf64_Sym *memsafe_lib_syms = NULL;
static const char *memsafe_lib_strs = NULL;
static size_t memsafe_lib_nsyms = 0;
static int memsafe_lib_loaded = 0;

static void memsafeLoadLibSyms(const char *path) {
    memsafe_lib_loaded = 1;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < (off_t)sizeof(Elf64_Ehdr)) {
        close(fd);
        return;
    }
    u8 *map = mmap(NULL, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return;
    Elf64_Ehdr *eh = (Elf64_Ehdr *)map;
    if (memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0 ||
        eh->e_ident[EI_CLASS] != ELFCLASS64 ||
        eh->e_shoff + (u64)eh->e_shnum * sizeof(Elf64_Shdr) > (u64)st.st_size)
        return;
    Elf64_Shdr *sh = (Elf64_Shdr *)(map + eh->e_shoff);
    for (int i = 0; i < eh->e_shnum; ++i) {
        if (sh[i].sh_type != SHT_DYNSYM || sh[i].sh_link >= eh->e_shnum) continue;
        Elf64_Shdr *strs = &sh[sh[i].sh_link];
        if (sh[i].sh_offset + sh[i].sh_size > (u64)st.st_size ||
            strs->sh_offset + strs->sh_size > (u64)st.st_size)
            return;
        memsafe_lib_syms = (const Elf64_Sym *)(map + sh[i].sh_offset);
        memsafe_lib_nsyms = sh[i].sh_size / sizeof(Elf64_Sym);
        memsafe_lib_strs = (const char *)(map + strs->sh_offset);
        return;
    }
}

static const char *memsafeLibSymbol(const char *path, uintptr_t off) {
    if (!memsafe_lib_loaded) memsafeLoadLibSyms(path);
    const char *best = NULL;
    uintptr_t best_at = 0;
    for (size_t i = 0; i < memsafe_lib_nsyms; ++i) {
        const Elf64_Sym *sy = &memsafe_lib_syms[i];
        if (sy->st_shndx == SHN_UNDEF || sy->st_value == 0) continue;
        if (sy->st_value <= off && sy->st_value >= best_at) {
            best_at = sy->st_value;
            best = memsafe_lib_strs + sy->st_name;
        }
    }
    return best;
}
#endif

/* Is `pc` in the prebuilt stdlib (libktm)? Its function's symbol in
 * *sym when asked. */
static int memsafeInLibktm(uintptr_t pc, const char **sym) {
    Dl_info info;
    if (!dladdr((void *)pc, &info) || !info.dli_fname) return 0;
    const char *base = strrchr(info.dli_fname, '/');
    base = base ? base + 1 : info.dli_fname;
    if (strncmp(base, "libktm", 6) != 0) return 0;
    if (sym) {
        *sym = info.dli_sname;
#if defined(__linux__)
        if (*sym == NULL)
            *sym = memsafeLibSymbol(info.dli_fname,
                                    pc - (uintptr_t)info.dli_fbase);
#endif
        if (*sym == NULL) return 0;
    }
    return 1;
}

/* First `max` return addresses landing in JIT code, walking this
 * (healthy) C call stack: wrapper <- _FREE/_MALLOC (JIT) <- user fn
 * (JIT) <- entry (C, stops matching). */
static int memsafeCallSites(uintptr_t *out, int max) {
    KtmJit *jit = memsafe_jit;
    if (jit == NULL) return 0;
    int n = 0;
    uintptr_t fp = (uintptr_t)__builtin_frame_address(0);
    uintptr_t prev = 0;
    /* A thread's chain doesn't end in a NULL frame pointer the way the
     * main thread's does (its start routine may not keep one): stay
     * inside this thread's stack. */
    uintptr_t lo, hi;
    memsafeThreadStack(&lo, &hi);
    for (int depth = 0; depth < 32 && fp && n < max; ++depth) {
        if (fp & 7) break;
        if (prev && fp <= prev) break;
        if (hi > lo && (fp < lo || fp + 2 * sizeof(uintptr_t) > hi)) break;
        uintptr_t ret = ((uintptr_t *)fp)[1];
        uintptr_t next = ((uintptr_t *)fp)[0];
        if (ret && (ktmJitFindChunk(jit, (void *)ret) ||
                    memsafeInLibktm(ret, NULL)))
            out[n++] = ret;
        prev = fp;
        fp = next;
    }
    return n;
}

/* The stdlib shims (`_MALLOC`, `Free`, `ReAlloc`, ...) sit between the
 * wrapper and the user's code, so skip them when naming a site. */
static int memsafeIsAllocShim(const char *sym) {
    while (*sym == '_') sym++;
    return strcasecmp(sym, "malloc") == 0 ||
           strcasecmp(sym, "free") == 0 ||
           strcasecmp(sym, "calloc") == 0 ||
           strcasecmp(sym, "realloc") == 0 ||
           strcasecmp(sym, "mallocident") == 0 ||
           strcasecmp(sym, "msize") == 0;
}

/* A frame of a report: function, source file and line */
typedef struct MemsafeFrame {
    const char *fn;
    const char *file;
    int line;
    int is_lib;
} MemsafeFrame;

/* The stdlib's directory (<install_dir>/modules/), "" if unknown */
static const char *memsafeLibDir(void) {
    static char dir[4096];
    static int done = 0;
    if (!done) {
        done = 1;
        dir[0] = '\0';
        if (memsafe_jit && memsafe_jit->cc && memsafe_jit->cc->install_dir)
            snprintf(dir, sizeof(dir), "%s/modules/",
                     memsafe_jit->cc->install_dir);
    }
    return dir;
}

/* `file` as it is shown: a stdlib file by its module path (vec/vector.ktm),
 * one under the working directory relative to it */
static const char *memsafeShownFile(const char *file, int *is_lib) {
    *is_lib = 0;
    if (file == NULL) return NULL;
    const char *lib = memsafeLibDir();
    size_t n = strlen(lib);
    if (n && strncmp(file, lib, n) == 0) {
        *is_lib = 1;
        return file + n;
    }
    static char cwd[4096];
    if (getcwd(cwd, sizeof(cwd))) {
        size_t c = strlen(cwd);
        if (c > 1 && strncmp(file, cwd, c) == 0 && file[c] == '/')
            return file + c + 1;
    }
    if (file[0] == '.' && file[1] == '/') return file + 2;
    return file;
}

static int memsafeFrame(uintptr_t site, MemsafeFrame *fr) {
    KtmJit *jit = memsafe_jit;
    size_t off = 0;
    const char *sym = jit
        ? ktmJitFindSymbolForAddr(jit, (void *)site, &off)
        : NULL;
    int in_lib = 0;
    if (sym == NULL && memsafeInLibktm(site, &sym)) in_lib = 1;
    if (sym == NULL || memsafeIsAllocShim(sym)) return 0;
    const char *shown = sym;
#if defined(__APPLE__)
    /* The JIT's Mach-O symbols carry an extra leading `_`; dladdr's
     * don't, so a module symbol from libktm arrives as K3str4from */
    static char kbuf[512];
    if (in_lib && shown[0] == 'K' && shown[1] >= '0' && shown[1] <= '9') {
        snprintf(kbuf, sizeof(kbuf), "_%s", shown);
        shown = kbuf;
    } else if (!in_lib && shown[0] == '_' && shown[1] != 'K') {
        shown++;
    }
#endif
    fr->fn = astSymbolDisplayName(shown);
    /* mem's own functions are the allocator, not who asked for it */
    if (!strcmp(fr->fn, "mem.alloc") || !strcmp(fr->fn, "mem.free") ||
        !strcmp(fr->fn, "mem.allocZero") || !strcmp(fr->fn, "mem.realloc"))
        return 0;
    if (in_lib) {
        fr->file = NULL;
        fr->line = 0;
        fr->is_lib = 1;
        return 1;
    }
    if (!strcmp(fr->fn, "MainFn") || !strcmp(fr->fn, "_MainFn") ||
        !strcmp(fr->fn, "Main") || !strcmp(fr->fn, "_Main"))
        fr->fn = "main";
    /* sites[] are return addresses - the CALL is the instruction
     * before, so look the line up at addr-1 or the report reads one
     * statement late. */
    size_t fl = strlen(fr->fn);
    if (fl > 6 && !strcmp(fr->fn + fl - 6, "._init")) {
        static char init_name[256];
        snprintf(init_name, sizeof(init_name), "%.*s's globals",
                 (int)(fl - 6), fr->fn);
        fr->fn = init_name;
    }
    fr->line = ktmJitFindLineForAddr(jit, (void *)(site - 1));
    fr->file = memsafeShownFile(ktmJitFindFileForSymbol(jit, sym),
                                &fr->is_lib);
    return 1;
}

static void memsafePrintFrame(FILE *f, MemsafeFrame *fr) {
    if (fr->file && fr->line) {
        fprintf(f, "at %s:%d in %s", fr->file, fr->line, fr->fn);
    } else if (fr->line) {
        fprintf(f, "in %s, line %d", fr->fn, fr->line);
    } else {
        fprintf(f, "in %s", fr->fn);
    }
}

/* Where a block was allocated or freed: the first line of the program's
 * own code on the call stack, and the stdlib function it called when the
 * block came from there ("at cli.ktm:12 in init, via vec.new<String>") */
static void memsafePrintSite(FILE *f, const uintptr_t *sites, int nsites) {
    MemsafeFrame user, lib_called;
    int have_user = 0, have_lib = 0;
    uintptr_t unknown = 0;
    for (int i = 0; i < nsites && !have_user; ++i) {
        if (sites[i] == 0) continue;
        MemsafeFrame fr;
        if (!memsafeFrame(sites[i], &fr)) {
            if (!unknown) unknown = sites[i];
            continue;
        }
        /* lib_called ends as the outermost stdlib function: the one the
         * program called */
        if (fr.is_lib) { lib_called = fr; have_lib = 1; }
        else { user = fr; have_user = 1; }
    }
    if (have_user) {
        memsafePrintFrame(f, &user);
        if (have_lib) fprintf(f, ", via %s", lib_called.fn);
    } else if (have_lib) {
        memsafePrintFrame(f, &lib_called);
    } else if (unknown) {
        fprintf(f, "at %p", (void *)unknown);
    } else {
        fprintf(f, "at an unknown site");
    }
}

/* "round N " when rounds are in play (the REPL); nothing otherwise. */
static void memsafePrintRound(FILE *f, int round) {
    if (round > 0) fprintf(f, "round %d ", round);
}

static void memsafeTrack(void *p, u64 size) {
    if (memsafe_live == NULL || p == NULL) return;
    u64 key = (u64)(uintptr_t)p;
    /* A stale entry can exist if host code freed this pointer behind
     * our back and libc has now reused the address so replace it. */
    MemsafeEntry *old = (MemsafeEntry *)mapGetInt(memsafe_live, key);
    if (old) {
        mapRemoveInt(memsafe_live, key);
        free(old);
    }
    MemsafeEntry *e = (MemsafeEntry *)calloc(1, sizeof(*e));
    if (e == NULL) return;
    e->ptr = p;
    e->size = size;
    e->alloc_round = memsafe_round;
    memsafeCallSites(e->alloc_site, MEMSAFE_SITES);
    mapAddIntOrErr(memsafe_live, key, e);
}

static MemsafeEntry *memsafeRingFind(void *p) {
    for (int i = 0; i < MEMSAFE_RING; ++i)
        if (memsafe_ring[i].ptr == p) return &memsafe_ring[i];
    return NULL;
}

/* Untrack a live pointer, moving it into the recently-freed ring and
 * releasing whichever block that pushes out. Returns 1 if it was
 * live; the caller must NOT free it. */
static int memsafeRetire(void *p) {
    if (memsafe_live == NULL || p == NULL) return 0;
    u64 key = (u64)(uintptr_t)p;
    MemsafeEntry *e = (MemsafeEntry *)mapGetInt(memsafe_live, key);
    if (e == NULL) return 0;
    mapRemoveInt(memsafe_live, key);
    e->free_round = memsafe_round;
    memset(e->free_site, 0, sizeof(e->free_site));
    memsafeCallSites(e->free_site, MEMSAFE_SITES);
    MemsafeEntry *slot = &memsafe_ring[memsafe_ring_next];
    if (slot->ptr) free((u8 *)slot->ptr - 8);
    *slot = *e;
    memsafe_ring_next = (memsafe_ring_next + 1) % MEMSAFE_RING;
    free(e);
    return 1;
}

static void memsafeReportDoubleFree(void *p, MemsafeEntry *r) {
    uintptr_t here[MEMSAFE_SITES] = { 0 };
    memsafeCallSites(here, MEMSAFE_SITES);
    fprintf(stderr, "Free: double free of %p (%llu bytes)\n",
                    p, (unsigned long long)r->size);
    fprintf(stderr, "  allocated   ");
    memsafePrintRound(stderr, r->alloc_round);
    memsafePrintSite(stderr, r->alloc_site, MEMSAFE_SITES);
    fprintf(stderr, "\n  first freed ");
    memsafePrintRound(stderr, r->free_round);
    memsafePrintSite(stderr, r->free_site, MEMSAFE_SITES);
    fprintf(stderr, "\n  this free   ");
    memsafePrintRound(stderr, memsafe_round);
    memsafePrintSite(stderr, here, MEMSAFE_SITES);
    fprintf(stderr, " - ignored, heap unharmed\n");
    fflush(stderr);
}

static void memsafeReportWildFree(void *p) {
    uintptr_t here[MEMSAFE_SITES] = { 0 };
    memsafeCallSites(here, MEMSAFE_SITES);
    fprintf(stderr, "Free: %p was never heap-allocated "
            "(stack, global or interior pointer?) ", p);
    memsafePrintSite(stderr, here, MEMSAFE_SITES);
    fprintf(stderr, " - ignored\n");
    fflush(stderr);
}

#if defined(__linux__)
/* glibc can't be asked whether an arbitrary pointer is a heap block,
 * so rule out what certainly isn't one: a MAlloc payload sits 8 past
 * a 16-aligned malloc block, and never on the stack, in a loaded
 * image's data, or in the JIT's code/globals. Anything else is taken
 * to be a block MAlloc'd inside libktm, which we can't see. */
static int memsafeIsWild(void *p) {
    uintptr_t a = (uintptr_t)p;
    if ((a & 15) != 8) return 1;
    if (a >= memsafe_stack_lo && a < memsafe_stack_hi) return 1;
    uintptr_t tlo, thi;  /* this thread's stack, when it isn't the main one */
    memsafeThreadStack(&tlo, &thi);
    if (a >= tlo && a < thi) return 1;
    Dl_info info;
    if (dladdr(p, &info)) return 1;
    if (memsafe_jit) {
        if (ktmJitFindChunk(memsafe_jit, p)) return 1;
        listForEach(memsafe_jit->globals_arenas) {
            uintptr_t lo = (uintptr_t)it->value;
            if (a >= lo && a < lo + malloc_usable_size(it->value)) return 1;
        }
        listForEach(memsafe_jit->string_arenas) {
            JitStrings *s = (JitStrings *)it->value;
            uintptr_t lo = (uintptr_t)s->ptr;
            if (a >= lo && a < lo + s->len) return 1;
        }
    }
    return 0;
}

static void memsafeFindStack(void) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (f == NULL) return;
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        unsigned long lo, hi;
        if (strstr(line, "[stack]") &&
            sscanf(line, "%lx-%lx", &lo, &hi) == 2) {
            memsafe_stack_lo = lo;
            memsafe_stack_hi = hi;
            break;
        }
    }
    fclose(f);
}
#endif

/* ---------------- the MAlloc-ABI wrappers ---------------- */

static void *memsafeMAlloc(u64 size) {
    u8 *base = (u8 *)malloc(size + 8);
    if (base == NULL) return NULL;
    *(u64 *)base = size;
    void *p = base + 8;
    memsafeLock();
    memsafeTrack(p, size);
    memsafeUnlock();
    return p;
}

static void *memsafeCAlloc(u64 size) {
    void *p = memsafeMAlloc(size);
    if (p) memset(p, 0, size);
    return p;
}

static void memsafeFreeLocked(void *p);

static void memsafeFree(void *p) {
    if (p == NULL) return;
    memsafeLock();
    memsafeFreeLocked(p);
    memsafeUnlock();
}

static void memsafeFreeLocked(void *p) {
    if (memsafeRetire(p)) return;
    MemsafeEntry *r = memsafeRingFind(p);
    if (r) {
        memsafeReportDoubleFree(p, r);
        return;
    }
#if defined(__APPLE__)
    /* Unknown pointer: probe the header address. Owned by a zone allocated by
     * MAlloc inside libktm/host code we can't see; the layout is identical, so
     * free the base. */
    if (malloc_zone_from_ptr((uint8_t *)p - 8)) {
        free((u8 *)p - 8);
        return;
    }
    memsafeReportWildFree(p);
#else
    if (memsafeIsWild(p)) {
        memsafeReportWildFree(p);
        return;
    }
    free((u8 *)p - 8);
#endif
}

static void *memsafeReAlloc(void *p, u64 size) {
    if (p == NULL) return memsafeMAlloc(size);
    memsafeLock();
    /* Refuse before touching the old block: a freed block's header
     * (and bytes) are not ours to read any more. */
    MemsafeEntry *r = memsafeRingFind(p);
    if (r != NULL) {
        memsafeReportDoubleFree(p, r);
        memsafeUnlock();
        return NULL;
    }
    void *q = memsafeMAlloc(size);
    if (q != NULL) {
        u64 old_size = *(u64 *)((u8 *)p - 8);
        memcpy(q, p, old_size < size ? old_size : size);
        memsafeFreeLocked(p);
    }
    memsafeUnlock();
    return q;
}

static int memsafeCmpSize(const void *a, const void *b) {
    const MemsafeEntry *ea = *(MemsafeEntry *const *)a;
    const MemsafeEntry *eb = *(MemsafeEntry *const *)b;
    if (ea->size != eb->size) return eb->size > ea->size ? 1 : -1;
    return 0;
}

/* Fill a Vec with the live entries (caller vecRelease's it - the vec
 * type's release is a no-op, entries stay owned by the map). */
static Vec *memsafeCollect(u64 *out_total) {
    Vec *v = vecNew(&vec_unsigned_long_type);
    u64 total = 0;
    MapIter mi;
    mapIterInit(memsafe_live, &mi);
    while (mapIterNext(&mi)) {
        MemsafeEntry *e = (MemsafeEntry *)mi.node->value;
        total += e->size;
        vecPush(v, e);
    }
    if (out_total) *out_total = total;
    return v;
}

static void memsafeList(FILE *f, Vec *v) {
    qsort(v->entries, v->size, sizeof(void *), memsafeCmpSize);
    u64 shown = v->size < 20 ? v->size : 20;
    for (u64 i = 0; i < shown; ++i) {
        MemsafeEntry *e = vecGet(MemsafeEntry *, v, i);
        fprintf(f, "  %p  %8llu bytes  ", e->ptr,
                (unsigned long long)e->size);
        memsafePrintRound(f, e->alloc_round);
        memsafePrintSite(f, e->alloc_site, MEMSAFE_SITES);
        fprintf(f, "\n");
    }
    if (v->size > shown)
        fprintf(f, "  ... %llu more\n",
                (unsigned long long)(v->size - shown));
}

void memsafeDumpLive(FILE *f) {
    if (memsafe_live == NULL) return;
    memsafeLock();
    u64 total = 0;
    Vec *v = memsafeCollect(&total);
    fprintf(f, "heap: %llu live allocation(s), %llu bytes\n",
            (unsigned long long)v->size, (unsigned long long)total);
    memsafeList(f, v);
    vecRelease(v);
    fflush(f);
    memsafeUnlock();
}

static int memsafe_reported = 0;
static pid_t memsafe_exit_pid = 0;

/* ---- what is still reachable at exit ----
 *
 * Like valgrind's "definitely lost" and "still reachable": a block some
 * root still points to (a global, libktm's data, or while exit() runs,
 * the stack and registers of the code that called it) is not a leak, as
 * the program could still have freed it; only a block nothing points to
 * is. Pointers are found conservatively: any aligned word holding an
 * address inside a live block (its start or anywhere in it). */

/* Where the program's code was when it called exit(): the top of the
 * stack to scan (0 = main returned: its variables are gone) */
static uintptr_t memsafe_exit_sp = 0;
static jmp_buf memsafe_exit_regs;

typedef struct MemsafeMark {
    MemsafeEntry **e;   /* sorted by address */
    u8 *marked;
    u64 n;
    u64 *work;
    u64 nwork;
} MemsafeMark;

static int memsafeCmpAddr(const void *a, const void *b) {
    uintptr_t pa = (uintptr_t)(*(MemsafeEntry *const *)a)->ptr;
    uintptr_t pb = (uintptr_t)(*(MemsafeEntry *const *)b)->ptr;
    return pa < pb ? -1 : pa > pb;
}

/* The block holding address w, -1 for none */
static s64 memsafeBlockAt(MemsafeMark *m, uintptr_t w) {
    s64 lo = 0, hi = (s64)m->n - 1, found = -1;
    while (lo <= hi) {
        s64 mid = lo + (hi - lo) / 2;
        if ((uintptr_t)m->e[mid]->ptr <= w) { found = mid; lo = mid + 1; }
        else hi = mid - 1;
    }
    if (found < 0) return -1;
    MemsafeEntry *e = m->e[found];
    if (w >= (uintptr_t)e->ptr + (e->size ? e->size : 1)) return -1;
    return found;
}

static void memsafeScan(MemsafeMark *m, uintptr_t lo, uintptr_t hi) {
    lo = (lo + sizeof(uintptr_t) - 1) & ~(uintptr_t)(sizeof(uintptr_t) - 1);
    for (uintptr_t a = lo; a + sizeof(uintptr_t) <= hi; a += sizeof(uintptr_t)) {
        uintptr_t w = *(uintptr_t *)a;
        if (w < 4096) continue;
        s64 i = memsafeBlockAt(m, w);
        if (i >= 0 && !m->marked[i]) {
            m->marked[i] = 1;
            m->work[m->nwork++] = (u64)i;
        }
    }
}

static size_t memsafeArenaSize(void *p) {
#if defined(__APPLE__)
    return malloc_size(p);
#else
    return malloc_usable_size(p);
#endif
}

/* libktm's writable data (its module globals: the default logger, ...) */
#if defined(__APPLE__)
static void memsafeScanLibData(MemsafeMark *m) {
    uint32_t count = _dyld_image_count();
    for (uint32_t i = 0; i < count; ++i) {
        const char *name = _dyld_get_image_name(i);
        const char *base = name ? strrchr(name, '/') : NULL;
        base = base ? base + 1 : name;
        if (!base || strncmp(base, "libktm", 6) != 0) continue;
        const struct mach_header_64 *mh =
            (const struct mach_header_64 *)_dyld_get_image_header(i);
        intptr_t slide = _dyld_get_image_vmaddr_slide(i);
        const u8 *lc = (const u8 *)(mh + 1);
        for (uint32_t c = 0; c < mh->ncmds; ++c) {
            const struct load_command *cmd = (const struct load_command *)lc;
            if (cmd->cmd == LC_SEGMENT_64) {
                const struct segment_command_64 *seg =
                    (const struct segment_command_64 *)cmd;
                if ((seg->initprot & VM_PROT_WRITE) && seg->vmsize) {
                    uintptr_t lo = (uintptr_t)seg->vmaddr + slide;
                    memsafeScan(m, lo, lo + seg->vmsize);
                }
            }
            lc += cmd->cmdsize;
        }
    }
}
#else
static int memsafeLibDataCb(struct dl_phdr_info *info, size_t size, void *arg) {
    (void)size;
    const char *base = info->dlpi_name ? strrchr(info->dlpi_name, '/') : NULL;
    base = base ? base + 1 : info->dlpi_name;
    if (!base || strncmp(base, "libktm", 6) != 0) return 0;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD || !(ph->p_flags & PF_W)) continue;
        uintptr_t lo = info->dlpi_addr + ph->p_vaddr;
        memsafeScan((MemsafeMark *)arg, lo, lo + ph->p_memsz);
    }
    return 0;
}

static void memsafeScanLibData(MemsafeMark *m) {
    dl_iterate_phdr(memsafeLibDataCb, m);
}
#endif

/* Mark every block reachable from the roots; marked[i] for m->e[i] */
static void memsafeMarkReachable(MemsafeMark *m) {
    if (memsafe_jit) {
        listForEach(memsafe_jit->globals_arenas) {
            uintptr_t lo = (uintptr_t)it->value;
            memsafeScan(m, lo, lo + memsafeArenaSize(it->value));
        }
    }
    memsafeScanLibData(m);
    if (memsafe_exit_sp) {
        uintptr_t lo, hi;
        memsafeThreadStack(&lo, &hi);
#if defined(__linux__)
        if (hi <= lo) hi = memsafe_stack_hi;
#endif
        if (hi > memsafe_exit_sp) memsafeScan(m, memsafe_exit_sp, hi);
        memsafeScan(m, (uintptr_t)&memsafe_exit_regs,
                    (uintptr_t)&memsafe_exit_regs + sizeof(memsafe_exit_regs));
    }
    while (m->nwork) {
        MemsafeEntry *e = m->e[m->work[--m->nwork]];
        memsafeScan(m, (uintptr_t)e->ptr, (uintptr_t)e->ptr + e->size);
    }
}

void memsafeReportLeaks(FILE *f) {
    memsafe_reported = 1;
    if (memsafe_live == NULL) return;
    memsafeLock();
    if (memsafe_live->size == 0) {
        memsafeUnlock();
        return;
    }
    /* The program's buffered stdout should land before the report. */
    fflush(stdout);
    u64 total = 0;
    Vec *v = memsafeCollect(&total);
    MemsafeMark m;
    m.n = v->size;
    m.e = malloc(sizeof(MemsafeEntry *) * m.n);
    m.marked = calloc(m.n, 1);
    m.work = malloc(sizeof(u64) * m.n);
    m.nwork = 0;
    for (u64 i = 0; i < m.n; ++i) m.e[i] = vecGet(MemsafeEntry *, v, i);
    qsort(m.e, m.n, sizeof(MemsafeEntry *), memsafeCmpAddr);
    memsafeMarkReachable(&m);

    Vec *lost = vecNew(&vec_unsigned_long_type);
    u64 lost_bytes = 0, kept = 0, kept_bytes = 0;
    for (u64 i = 0; i < m.n; ++i) {
        if (m.marked[i]) {
            kept++;
            kept_bytes += m.e[i]->size;
        } else {
            vecPush(lost, m.e[i]);
            lost_bytes += m.e[i]->size;
        }
    }
    if (lost->size) {
        fprintf(f, "memsafe: %llu allocation(s) (%llu bytes) never freed\n",
                (unsigned long long)lost->size, (unsigned long long)lost_bytes);
        memsafeList(f, lost);
    }
    if (kept) {
        fprintf(f, "memsafe: %llu allocation(s) (%llu bytes) still in use at "
                "exit (held by globals or live variables): not leaks\n",
                (unsigned long long)kept, (unsigned long long)kept_bytes);
    }
    vecRelease(lost);
    free(m.e);
    free(m.marked);
    free(m.work);
    vecRelease(v);
    fflush(f);
    memsafeUnlock();
}

/* A program that ends with exit() never returns from main: report then,
 * once, from the process that ran it (not a forked child) */
static void memsafeExitReport(void) {
    if (memsafe_reported || getpid() != memsafe_exit_pid) return;
    if (!memsafe_exit_sp) {
        setjmp(memsafe_exit_regs);
        memsafe_exit_sp = (uintptr_t)__builtin_frame_address(0);
    }
    memsafeReportLeaks(stderr);
}

/* (memsafe_exit_regs: the registers of the code that called exit(); a
 * register-allocated variable is as live as one on the stack) */

/* The program's exit(): note its caller's stack and registers, so what
 * its variables hold is still reachable, then the real exit. The scan
 * starts at the caller's frame, not below it: a function that returned
 * before exit() left stale pointers down there. */
static void memsafeExit(int code) {
    setjmp(memsafe_exit_regs);
    memsafe_exit_sp = (uintptr_t)__builtin_frame_address(0) + 2 * sizeof(uintptr_t);
    exit(code);
}

void memsafeReportAtExit(void) {
    memsafe_exit_pid = getpid();
    atexit(memsafeExitReport);
}

/* libktm's hook `name` (a module global, by its symbol) set to `fn`; the
 * JIT has loaded libktm by now. Nothing when it isn't there. */
static void memsafeSetHook(const char *name, void *fn) {
    char *sym = astModuleMangle((char *)name, (int)strlen(name));
    void **slot = NULL;
#if defined(__APPLE__)
    /* Mach-O: dlsym adds the leading `_` itself, and ktm's own symbols
     * are emitted without the extra one a C name gets */
    slot = (void **)dlsym(RTLD_DEFAULT, sym + 1);
#endif
    if (!slot) slot = (void **)dlsym(RTLD_DEFAULT, sym);
    if (slot) *slot = fn;
}

void memsafeInit(KtmJit *jit) {
    if (jit == NULL) return;
    memsafe_jit = jit;
    if (!memsafe_lock_ready) {
        pthread_mutexattr_t attr;
        pthread_mutexattr_init(&attr);
        pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
        pthread_mutex_init(&memsafe_lock, &attr);
        pthread_mutexattr_destroy(&attr);
        memsafe_lock_ready = 1;
    }
#if defined(__linux__)
    if (memsafe_stack_hi == 0) memsafeFindStack();
#endif
    if (memsafe_live == NULL)
        memsafe_live = mapNew(64, &map_uint_to_uint_type);
    /* host_symbols wins over dlsym, so these shadow the libktm dylib's
     * implementations at every JIT'd call site. Both the asm-label
     * spellings (_MALLOC on x86_64) and the compiled-ktm ones (Free
     * on aarch64) are covered; register before any chunk compiles. */
    /* exact names: "_exit" on Linux is the other function */
#if defined(__APPLE__)
    mapAdd(jit->host_symbols, strdup("_exit"), (void *)(uintptr_t)memsafeExit);
#else
    mapAdd(jit->host_symbols, strdup("exit"), (void *)(uintptr_t)memsafeExit);
#endif
    ktmJitDefineSymbol(jit, "_MALLOC",  (void *)(uintptr_t)memsafeMAlloc);
    ktmJitDefineSymbol(jit, "_CALLOC",  (void *)(uintptr_t)memsafeCAlloc);
    ktmJitDefineSymbol(jit, "_REALLOC", (void *)(uintptr_t)memsafeReAlloc);
    ktmJitDefineSymbol(jit, "_FREE",    (void *)(uintptr_t)memsafeFree);
    ktmJitDefineSymbol(jit, "Free",     (void *)(uintptr_t)memsafeFree);
    ktmJitDefineSymbol(jit, "CAlloc",   (void *)(uintptr_t)memsafeCAlloc);
    ktmJitDefineSymbol(jit, "ReAlloc",  (void *)(uintptr_t)memsafeReAlloc);
    /* the stdlib as modules: mem's functions by their symbols */
    ktmJitDefineSymbol(jit, "_K3mem5alloc",     (void *)(uintptr_t)memsafeMAlloc);
    ktmJitDefineSymbol(jit, "_K3mem4free",      (void *)(uintptr_t)memsafeFree);
    ktmJitDefineSymbol(jit, "_K3mem9allocZero", (void *)(uintptr_t)memsafeCAlloc);
    ktmJitDefineSymbol(jit, "_K3mem7realloc",   (void *)(uintptr_t)memsafeReAlloc);
    /* and libktm's own allocations (a String growing, str.format's
     * buffers): its mem functions call these hooks when they are set */
    memsafeSetHook("mem.__allocHook", (void *)(uintptr_t)memsafeMAlloc);
    memsafeSetHook("mem.__freeHook", (void *)(uintptr_t)memsafeFree);
    memsafeSetHook("mem.__allocZeroHook", (void *)(uintptr_t)memsafeCAlloc);
    memsafeSetHook("mem.__reallocHook", (void *)(uintptr_t)memsafeReAlloc);
}
