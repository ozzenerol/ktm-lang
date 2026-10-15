/* Arch-independent half of the in-process JIT. */

#include <ctype.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include "aostr.h"
#include "asm.h"
#include "cli.h"
#include "config.h"
#include "prsasm.h"
#include "ir-optimise.h"
#include "jit-common.h"
#include "list.h"
#include "memsafe.h"
#include "util.h"

/* Internal labels (cross-function calls within this TU) are matched
 * inside asm_jit_finalize via enc->labels by name -- the resolver only
 * sees external symbols. Host overrides win over libc. */
static void *jitResolveSymbol(void *ud, const char *sym) {
    return ktmJitSymbolAddr(ud, sym);
}

/* The address of a symbol JIT code uses: the JIT's own (or a host
 * override), else what dlsym finds */
void *ktmJitSymbolAddr(KtmJit *jit, const char *sym) {
    void *p;
    if ((p = mapGet(jit->host_symbols, (void *)sym))) return p;
    /* A threadlocal of a prebuilt library: dlsym gives this thread's copy
     * of it, which isn't the storage every thread's JIT code would need */
    Set *tls = jit->cc->jit_tls_syms;
    if (tls && (setHas(tls, (char *)sym) ||
                (sym[0] == '_' && setHas(tls, (char *)sym + 1)))) {
        fprintf(stderr, "error: threadlocal `%s` is defined in a prebuilt "
                "library, which JIT code can't reach: read it through a "
                "function of the library, or compile ahead of time\n", sym);
        exit(EXIT_FAILURE);
    }
    return asm_jit_dlsym_resolver(NULL, sym);
}

int ktmJitBlockLocalNum(KtmJit *jit, IrFunction *fn, IrBlock *block) {
    uint64_t key = ((uint64_t)fn->uuid << 32) | (uint64_t)block->id;
    void *v = mapGetInt(jit->block_local, key);
    if (v) return (int)(intptr_t)v;
    int n = ++jit->next_local;
    mapAddIntOrErr(jit->block_local, key, (void *)(intptr_t)n);
    return n;
}

int ktmJitEpilogueLocalNum(KtmJit *jit, IrFunction *fn) {
    void *v = mapGetInt(jit->epi_local, (uint64_t)fn->uuid);
    if (v) return (int)(intptr_t)v;
    int n = ++jit->next_local;
    mapAddIntOrErr(jit->epi_local, (uint64_t)fn->uuid, (void *)(intptr_t)n);
    return n;
}

int ktmJitFreshLocalNum(KtmJit *jit) {
    return ++jit->next_local;
}

int ktmJitIsInternalFunc(KtmJit *jit, const char *sym) {
    /* "Internal" means defined in the chunk currently being emitted -
     * those calls route through label fixups. Functions from earlier
     * chunks resolve through host_symbols like any other host address. */
    return mapGet(jit->chunk_fns, (void *)sym) != NULL;
}

/* ---------------- threadlocal globals ---------------- */

/* JIT code can't use ELF/Mach-O TLS, so a threadlocal global is laid out
 * once like any global (holding its initial value) and each thread gets
 * its own copy of that storage on first use: `image` is the global's
 * address, the table maps it to this thread's copy. Freed at thread exit. */
typedef struct JitTlsEnt {
    void *image;
    void *copy;
} JitTlsEnt;

typedef struct JitTlsTab {
    JitTlsEnt *ents;
    size_t cap;
    size_t len;
} JitTlsTab;

static __thread JitTlsTab *jit_tls_tab = NULL;
static pthread_key_t jit_tls_key;
static pthread_once_t jit_tls_once = PTHREAD_ONCE_INIT;

static void jitTlsFree(void *p) {
    JitTlsTab *tab = p;
    for (size_t i = 0; i < tab->cap; ++i) free(tab->ents[i].copy);
    free(tab->ents);
    free(tab);
}

static void jitTlsKeyCreate(void) {
    pthread_key_create(&jit_tls_key, jitTlsFree);
}

static size_t jitTlsSlot(JitTlsEnt *ents, size_t cap, void *image) {
    size_t i = (((size_t)image) >> 3) * 0x9E3779B97F4A7C15ULL & (cap - 1);
    while (ents[i].image && ents[i].image != image) i = (i + 1) & (cap - 1);
    return i;
}

static void jitTlsGrow(JitTlsTab *tab) {
    size_t cap = tab->cap ? tab->cap * 2 : 16;
    JitTlsEnt *ents = calloc(cap, sizeof(JitTlsEnt));
    if (!ents) {
        fprintf(stderr, "out of memory for a threadlocal global\n");
        exit(EXIT_FAILURE);
    }
    for (size_t i = 0; i < tab->cap; ++i) {
        if (!tab->ents[i].image) continue;
        ents[jitTlsSlot(ents, cap, tab->ents[i].image)] = tab->ents[i];
    }
    free(tab->ents);
    tab->ents = ents;
    tab->cap = cap;
}

/* The calling thread's copy of the threadlocal global at `image` */
static void *ktmJitTls(void *image, long size) {
    JitTlsTab *tab = jit_tls_tab;
    if (tab) {
        size_t i = jitTlsSlot(tab->ents, tab->cap, image);
        if (tab->ents[i].image) return tab->ents[i].copy;
    } else {
        pthread_once(&jit_tls_once, jitTlsKeyCreate);
        tab = calloc(1, sizeof(JitTlsTab));
        if (!tab) {
            fprintf(stderr, "out of memory for a threadlocal global\n");
            exit(EXIT_FAILURE);
        }
        jitTlsGrow(tab);
        jit_tls_tab = tab;
        pthread_setspecific(jit_tls_key, tab);
    }
    if ((tab->len + 1) * 2 > tab->cap) jitTlsGrow(tab);
    void *copy = malloc(size > 0 ? (size_t)size : 1);
    if (!copy) {
        fprintf(stderr, "out of memory for a threadlocal global\n");
        exit(EXIT_FAILURE);
    }
    memcpy(copy, image, (size_t)size);
    size_t i = jitTlsSlot(tab->ents, tab->cap, image);
    tab->ents[i].image = image;
    tab->ents[i].copy = copy;
    tab->len++;
    return copy;
}

/* ---------------- fixup helpers ---------------- */

void ktmJitAddCallFixup(AsmEnc *enc, size_t patch_off, const char *sym,
                        AsmFixupReloc reloc)
{
    AsmFixup f = {
        .kind = AF_SYMBOL,
        .reloc = reloc,
        .patch_offset = patch_off,
        .width = 4,
        .pcrel = 1,
        .sym = strdup(sym),
    };
    asm_add_fixup(enc, f);
}

void ktmJitAddLocalBranchFixup(AsmEnc *enc, size_t patch_off, int local_num,
                               AsmFixupReloc reloc)
{
    AsmFixup f = {
        .kind = AF_LOCAL,
        .reloc = reloc,
        .patch_offset = patch_off,
        .width = 4,
        .pcrel = 1,
        .local_num = local_num,
    };
    asm_add_fixup(enc, f);
}

#define JIT_MAX_PHIS 32
void ktmJitPhiMaterialise(IrBlock *from, IrBlock *to,
                          KtmJitEmitPhiFn emit, void *ud)
{
    if (!to || !from) return;
    IrInstr *phis[JIT_MAX_PHIS];
    IrPair  *pairs[JIT_MAX_PHIS];
    int     done[JIT_MAX_PHIS];
    int n = 0;

    listForEach(to->instructions) {
        IrInstr *I = (IrInstr *)it->value;
        if (I->op == IR_NOP) continue;
        if (I->op != IR_PHI) break;
        if (n >= JIT_MAX_PHIS) loggerPanic("jit: too many phis\n");
        IrPair *match = NULL;
        if (I->extra.phi_pairs) {
            for (u64 i = 0; i < I->extra.phi_pairs->size; ++i) {
                IrPair *p = vecGet(IrPair *, I->extra.phi_pairs, i);
                if (p->ir_block == from) { match = p; break; }
            }
        }
        if (!match || !match->ir_value) continue;
        phis[n] = I; pairs[n] = match; done[n] = 0; n++;
    }
    if (n == 0) return;

    /* Order so that no pending phi reads a value an earlier emit has
     * already overwritten; a cycle would need a temp and can't occur
     * with the IR builder's current output. */
    int emitted = 0;
    while (emitted < n) {
        int progress = 0;
        for (int i = 0; i < n; ++i) {
            if (done[i]) continue;
            int read_by_pending = 0;
            for (int j = 0; j < n; ++j) {
                if (i == j || done[j]) continue;
                IrValue *v = pairs[j]->ir_value;
                if (irIsTmp(v) && irIsTmp(phis[i]->dst) &&
                    irVarId(v) == irDstVarId(phis[i])) {
                    read_by_pending = 1; break;
                }
            }
            if (!read_by_pending) {
                emit(ud, phis[i], pairs[i]);
                done[i] = 1; emitted++; progress = 1;
            }
        }
        if (!progress)
            loggerPanic("jit: phi cycle at block %u\n", to->id);
    }
}

/* ---------------- asm {} blocks ---------------- */

int ktmJitAssembleText(KtmJit *jit, AoStr *text, int src_line) {
    if (!text || text->len == 0) return 0;
    Cctrl *cc = jit->cc;
    AsmBlock *blk = asm_parse_with_handler(text->data, (int)text->len,
                                           "<asm>", src_line,
                                           prsAsmTasmArch(cc),
                                           cc, prsAsmDiagSink);
    if (blk->errors) {
        asm_block_free(blk);
        return -1;
    }
    /* Encode into a private AsmEnc: libtasm resolves `@@N` local-label
     * fixups against the labels of THIS chunk only, which both scopes
     * them per function (the language's semantics) and keeps their numbers
     * from colliding with the jit's own anonymous local labels. */
    AsmEnc sub;
    asm_enc_init(&sub);
    asm_enc_set_error_handler(&sub, cc, prsAsmDiagSink);
    asm_encode(blk, NULL, &sub);
    asm_block_free(blk);
    if (sub.errors) {
        asm_enc_free(&sub);
        return -1;
    }

    /* Splice: bytes are appended at the current tail; named labels and
     * the remaining AF_SYMBOL fixups rebase onto it. */
    size_t base = jit->enc.len;
    for (int i = 0; i < sub.n_labels; ++i) {
        if (!sub.labels[i].name) continue; /* locals are resolved+dead */
        size_t save = jit->enc.len;
        jit->enc.len = base + sub.labels[i].byte_offset;
        asm_define_label(&jit->enc, -1, sub.labels[i].name);
        jit->enc.len = save;
    }
    for (size_t i = 0; i < sub.len; ++i) put_byte(&jit->enc, sub.bytes[i]);
    for (int i = 0; i < sub.n_fixups; ++i) {
        AsmFixup f = sub.fixups[i];
        f.patch_offset += base;
        if (f.sym) f.sym = strdup(f.sym);
        asm_add_fixup(&jit->enc, f);
    }
    asm_enc_free(&sub);
    return 0;
}

/* ---------------- globals layout ---------------- */

/* The mangled arena label of a global decl, or NULL if it doesn't get
 * a slot (extern, typeless, ...). */
static AoStr *jitGlobalLabel(Ast *ast) {
    if (ast->kind != AST_DECL && ast->kind != AST_GVAR) return NULL;
    Ast *dv = ast->declvar;
    if (!dv || !dv->type) return NULL;
    if (ast->flags & AST_FLAG_EXTERN) return NULL;
    if (dv->flags & AST_FLAG_EXTERN) return NULL;
    return (dv->glabel ? dv->glabel : dv->gname);
}

/* Estimate the size needed for the not-yet-allocated globals in
 * (from, sentinel] + string literals. Entries already registered in
 * host_symbols (allocated by a previous chunk) are skipped. */
static size_t jitGlobalsSize(KtmJit *jit, List *from) {
    Cctrl *cc = jit->cc;
    size_t total = 0;
    for (List *it = from; it != cc->ast_list; it = it->next) {
        Ast *ast = it->value;
        AoStr *label = jitGlobalLabel(ast);
        if (!label) continue;
        if (mapGet(jit->host_symbols, (void *)label->data)) continue;
        total += (ast->declvar->type->size + 7) & ~7;  /* 8-byte align */
    }
    return total;
}

/* The bytes the new string literals take (cc->strs accumulates across
 * chunks; the host_symbols check skips already-laid-out ones) */
static size_t jitStringsSize(KtmJit *jit) {
    Cctrl *cc = jit->cc;
    size_t total = 0;
    if (cc->strs) {
        MapIter mi; mapIterInit(cc->strs, &mi);
        while (mapIterNext(&mi)) {
            Ast *ast = (Ast *)mi.node->value;
            if (ast->kind != AST_STRING) continue;
            if (mapGet(jit->host_symbols, (void *)ast->slabel->data)) continue;
            total += ((size_t)ast->sval->len + 1 + 7) & ~7;
        }
    }
    return total;
}

/* Lay out the new string literals in a mapping of their own, made
 * read-only once written: a write through a literal faults, as it does in
 * a compiled program, instead of changing every later use of it */
static int jitAllocateStrings(KtmJit *jit) {
    size_t sz = jitStringsSize(jit);
    if (sz == 0) return 0;
    long page = sysconf(_SC_PAGESIZE);
    sz = (sz + (size_t)page - 1) & ~((size_t)page - 1);
    uint8_t *strs = mmap(NULL, sz, PROT_READ | PROT_WRITE,
                         MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (strs == MAP_FAILED) return -1;
    JitStrings *arena = (JitStrings *)malloc(sizeof(JitStrings));
    arena->ptr = strs;
    arena->len = sz;
    listAppend(jit->string_arenas, arena);
    size_t off = 0;
    MapIter mi; mapIterInit(jit->cc->strs, &mi);
    while (mapIterNext(&mi)) {
        Ast *ast = (Ast *)mi.node->value;
        if (ast->kind != AST_STRING) continue;
        if (mapGet(jit->host_symbols, (void *)ast->slabel->data)) continue;
        char *addr = (char *)(strs + off);
        /* Decoded: the asm path lets the assembler decode the
         * escapes, so JIT-emitted code sees the same byte image. */
        int o = astStringDecode(ast, addr);
        mapAdd(jit->host_symbols, strdup(ast->slabel->data), addr);
        /* Allocate based on the *decoded* length plus NUL, 8-aligned. */
        size_t decoded_size = (size_t)(o + 1);
        off += (decoded_size + 7) & ~7;
    }
    mprotect(strs, sz, PROT_READ);
    return 0;
}

static void jitStringsFree(void *p) {
    JitStrings *arena = (JitStrings *)p;
    munmap(arena->ptr, arena->len);
    free(arena);
}

/* Write an initialiser's byte image (asmInitImage: fields at their
 * offsets, zeros elsewhere) for a `type` object to `addr`, which is
 * already zeroed. String literals become 8-byte pointers to the
 * string's slot in the arena (looked up via host_symbols, populated in
 * pass 1). */
static void jitWriteInit(KtmJit *jit, uint8_t *addr, Ast *init,
                         AstType *type)
{
    if (!init || type->size <= 0) return;
    Ast **items = (Ast **)calloc((size_t)type->size, sizeof(Ast *));
    asmInitImage(init, type, addr, items);
    for (int i = 0; i < type->size; ++i) {
        if (!items[i] || items[i]->kind != AST_STRING) continue;
        void *p = mapGet(jit->host_symbols, (void *)items[i]->slabel->data);
        uintptr_t v = (uintptr_t)p;
        memcpy(addr + i, &v, 8);
    }
    free(items);
}

typedef struct JitPtrFixup {
    uint8_t *addr;  /* the pointer's slot in the globals arena */
    char *sym;
    s64 off;
} JitPtrFixup;

/* Fill in the global pointers whose targets have an address now: a
 * global (laid out with the chunk) or a function (once its chunk is
 * finalized), or a library symbol. The others wait for a later chunk. */
static void jitResolvePtrFixups(KtmJit *jit) {
    List *it = jit->ptr_fixups->next;
    while (it != jit->ptr_fixups) {
        List *next = it->next;
        JitPtrFixup *f = (JitPtrFixup *)it->value;
        void *p = jitResolveSymbol(jit, f->sym);
        if (p) {
            uintptr_t v = (uintptr_t)p + (uintptr_t)f->off;
            memcpy(f->addr, &v, 8);
            it->prev->next = next;
            next->prev = it->prev;
            free(f->sym);
            free(f);
            free(it);
        }
        it = next;
    }
}

/* Lay out the (from, sentinel] range's new strings + globals into a
 * fresh arena; register each by its mangled name so the backends'
 * global-address emitters can find them via host_symbols. Strings come
 * first so that AST_ARRAY_INIT initialisers in globals can reference
 * them by their already-known addresses. Anything a previous chunk
 * already laid out keeps its old (registered) address. */
static int jitAllocateGlobals(KtmJit *jit, List *from) {
    if (jitAllocateStrings(jit) != 0) return -1;
    size_t sz = jitGlobalsSize(jit, from);
    if (sz == 0) return 0;
    sz += 64; /* slack */
    uint8_t *globals = calloc(1, sz);
    listAppend(jit->globals_arenas, globals);
    size_t off = 0;

    /* Globals. Strings are now addressable, so AST_ARRAY_INIT
     * containing AST_STRING elements can resolve element addresses. */
    AoStr *sym;
    s64 sym_off;
    for (List *it = from; it != jit->cc->ast_list; it = it->next) {
        Ast *ast = it->value;
        AoStr *label = jitGlobalLabel(ast);
        if (!label) continue;
        if (mapGet(jit->host_symbols, (void *)label->data)) continue;
        uint8_t *addr = globals + off;
        mapAdd(jit->host_symbols, strdup(label->data), addr);
        Ast *init = ast->declinit;
        if (init && init->kind == AST_STRING &&
            ast->declvar->type->kind == AST_TYPE_ARRAY) {
            /* `U8 buf[] = "abc"`: copy the (already decoded) string
             * bytes inline rather than a pointer to them. The arena is
             * calloc'd, so a larger `U8 buf[8]` is zero-filled. */
            void *src = mapGet(jit->host_symbols, (void *)init->slabel->data);
            if (src) memcpy(addr, src, (size_t)init->real_len);
        } else if (asmAddrConst(jit->cc, init, &sym, &sym_off, 1)) {
            JitPtrFixup *f = (JitPtrFixup *)malloc(sizeof(JitPtrFixup));
            f->addr = addr;
            f->sym = strdup(sym->data);
            f->off = sym_off;
            listAppend(jit->ptr_fixups, f);
        } else {
            jitWriteInit(jit, addr, init, ast->declvar->type);
        }
        off += (ast->declvar->type->size + 7) & ~7;
    }
    return 0;
}

/* Load libktm so ktm stdlib symbols (MAlloc, Free, printf, ...) are
 * discoverable by dlsym. The AOT path links them in at the linker step;
 * the JIT has to make them visible at runtime. The configured install
 * prefix (cc->install_dir, `--install-dir`, defaults to /usr/local) is
 * probed first with both platforms' install conventions: macOS installs
 * lib<name>.0.0.1.dylib, Linux lib<name>.so.0.0.1. Neither installs an
 * unversioned shared library - that would make `-lktm` prefer it over
 * the static archive (see ktmLibInit in main.c) - so the unversioned
 * entries here only ever match a hand-placed one. */
static void jitLoadLibtos(Cctrl *cc) {
    static int tos_loaded = 0;
    if (tos_loaded) return;
    tos_loaded = 1;

    if (cc->install_dir && *cc->install_dir) {
        static const char *const suffixes[] = {
            "lib/libktm.dylib",
            "lib/libktm.0.0.1.dylib",
            "lib/libktm.so",
            "lib/libktm.so.0.0.1",
        };
        char path[1024];
        for (size_t i = 0; i < sizeof(suffixes)/sizeof(suffixes[0]); ++i) {
            snprintf(path, sizeof(path), "%s/%s", cc->install_dir, suffixes[i]);
            if (dlopen(path, RTLD_LAZY | RTLD_GLOBAL)) return;
        }
    }
    /* Fall back to the dynamic-linker search paths and the in-tree
     * `ktm -lib ktm` build artifact. */
    static const char *const candidates[] = {
        "libktm.dylib",
        "libktm.so",
        "libktm.so.0.0.1",
        "./libktm.so",
        "./libktm.dylib",
    };
    for (size_t i = 0; i < sizeof(candidates)/sizeof(candidates[0]); ++i) {
        if (dlopen(candidates[i], RTLD_LAZY | RTLD_GLOBAL)) return;
    }
    /* Non-fatal if none load: programs that don't touch the ktm
     * stdlib will still work. The unresolved-symbol error fires later
     * with a precise name if they do. */
}

/* dlopen one library, making its exports visible to
 * asm_jit_dlsym_resolver. `name_form` entries came from `#link <name>`
 * and are probed with the linker's lib prefix + both platforms'
 * suffixes (dlopen inspects the file, not the extension, so trying
 * both is harmless); paths load verbatim. The versioned names are
 * what `ktm -lib <name>` installs (lib<name>.0.0.1.dylib /
 * lib<name>.so.0.0.1, no unversioned link - see ktmLibInit in
 * main.c), so they are probed too. Returns 1 on success. */
static int jitDlopenLib(Cctrl *cc, const char *name, int name_form) {
    if (!name_form) {
        return dlopen(name, RTLD_LAZY | RTLD_GLOBAL) != NULL;
    }

    /* Bare names first: dyld / ld.so search their own default paths
     * (DYLD_LIBRARY_PATH / LD_LIBRARY_PATH, the dyld shared cache,
     * the ldconfig cache, ...). */
    static const char *const fmts[] = {
        "lib%s.dylib", "lib%s.so", "lib%s.0.0.1.dylib", "lib%s.so.0.0.1", "%s"
    };
    char buf[1024];
    for (size_t i = 0; i < sizeof(fmts)/sizeof(fmts[0]); ++i) {
        snprintf(buf, sizeof(buf), fmts[i], name);
        if (dlopen(buf, RTLD_LAZY | RTLD_GLOBAL)) return 1;
    }

    /* Then the places libraries commonly live that the dynamic linker
     * does NOT search for bare names: the configured install prefix
     * (mirrors the AOT `-L<prefix>/lib`), Homebrew (dlopen never
     * searches /opt/homebrew/lib on Apple Silicon), the classic system
     * dirs and Debian/Fedora arch dirs. */
    char prefix_lib[1024];
    const char *dirs[8];
    size_t ndirs = 0;
    if (cc->install_dir && *cc->install_dir) {
        snprintf(prefix_lib, sizeof(prefix_lib), "%s/lib", cc->install_dir);
        dirs[ndirs++] = prefix_lib;
    }
#ifdef __APPLE__ 
    dirs[ndirs++] = "/opt/homebrew/lib";
#endif

    dirs[ndirs++] = "/usr/local/lib";
    dirs[ndirs++] = "/usr/lib";

#if defined(__x86_64__)
    dirs[ndirs++] = "/usr/lib/x86_64-linux-gnu";
#elif defined(__aarch64__) || defined(__arm64__)
    dirs[ndirs++] = "/usr/lib/aarch64-linux-gnu";
#endif

    dirs[ndirs++] = "/usr/lib64";

    for (size_t d = 0; d < ndirs; ++d) {
        char *lib = tprintf("%s/lib%s.dylib", dirs[d], name);
        if (dlopen(lib, RTLD_LAZY | RTLD_GLOBAL)) return 1;
        lib = tprintf("%s/lib%s.so", dirs[d], name);
        if (dlopen(lib, RTLD_LAZY | RTLD_GLOBAL)) return 1;
        lib = tprintf("%s/lib%s.0.0.1.dylib", dirs[d], name);
        if (dlopen(lib, RTLD_LAZY | RTLD_GLOBAL)) return 1;
        lib = tprintf("%s/lib%s.so.0.0.1", dirs[d], name);
        if (dlopen(lib, RTLD_LAZY | RTLD_GLOBAL)) return 1;
    }
    return 0;
}

/* Load every shared object the CLI or a `#link` directive asked for.
 * Called from ktmJitNew AND per chunk compile: in the REPL a `#link`
 * typed mid-session grows the lists after the JIT already exists.
 * The `loaded` set makes each entry load (and any failure report)
 * happen exactly once. */
static void jitLoadSharedObjects(Cctrl *cc) {
    static Set *loaded = NULL;
    if (loaded == NULL) loaded = setNew(32, &set_cstring_type);

    if (!listEmpty(cc->object_files)) {
        static int warned_object_files = 0;
        if (!warned_object_files) {
            warned_object_files = 1;
            fprintf(stderr, "Ignoring object files, only shared objects "
                    "can be loaded by the JIT\n");
        }
    }

    if (!listEmpty(cc->shared_object_files)) {
        listForEach(cc->shared_object_files) {
            AoStr *so = it->value;
            if (setHas(loaded, so->data)) continue;
            setAdd(loaded, so->data);
            if (!jitDlopenLib(cc, so->data, 0)) {
                fprintf(stderr, "ktm: #link: failed to load '%s': %s\n",
                        so->data, dlerror());
            }
        }
    }
    if (!listEmpty(cc->link_libs)) {
        listForEach(cc->link_libs) {
            AoStr *name = it->value;
            if (setHas(loaded, name->data)) continue;
            setAdd(loaded, name->data);
            if (!jitDlopenLib(cc, name->data, 1)) {
                fprintf(stderr, "ktm: #link: failed to load library '%s' "
                        "(probed lib%s.{dylib,so,0.0.1.dylib,so.0.0.1} in the "
                        "dynamic linker's "
                        "default paths, %s/lib, /opt/homebrew/lib, "
                        "/usr/local/lib and the system lib dirs)\n",
                        name->data, name->data,
                        cc->install_dir ? cc->install_dir : "");
            }
        }
    }
}

/* ---------------- compile pipeline ---------------- */

KtmJit *ktmJitNew(Cctrl *cc, const KtmJitBackend *backend) {
    if (!backend->target_ok(cc->target)) {
        fprintf(stderr, "ktm: JIT only supports %s targets\n", backend->name);
        return NULL;
    }

    KtmJit *jit = calloc(1, sizeof *jit);
    jit->cc             = cc;
    jit->backend        = backend;
    jit->symbols        = mapNew(64, &map_cstring_opaque_type);
    jit->host_symbols   = mapNew(64, &map_cstring_opaque_type);
    jit->sym_files      = mapNew(64, &map_cstring_cstring_type);
    jit->chunk_fns      = mapNew(64, &map_cstring_opaque_type);
    jit->chunks         = listNew();
    jit->globals_arenas = listNew();
    jit->string_arenas = listNew();
    jit->ptr_fixups = listNew();
    jit->block_local    = mapNew(64, &map_uint_to_uint_type);
    jit->epi_local      = mapNew(16, &map_uint_to_uint_type);
    jit->next_local     = 0;
    /* Nothing consumed yet: cursors sit on the sentinels so the first
     * chunk sweeps the whole lists. */
    jit->ast_cursor     = cc->ast_list;
    jit->asm_cursor     = cc->asm_blocks;

    asm_enc_init(&jit->enc);
    backend->init_reg_pool();
    jitLoadLibtos(cc);
    jitLoadSharedObjects(cc);
    /* both spellings: ktmJitDefineSymbol adds or drops one `_` */
    ktmJitDefineSymbol(jit, "___ktmJitTls", (void *)(uintptr_t)ktmJitTls);
    return jit;
}

/* Register a chunk-internal function name (emit-time calls/IR_LEA to
 * it route through label fixups instead of host_symbols/dlsym). */
/* Remember which file the function `fn` came from (for reports). */
static void jitNoteSymFile(KtmJit *jit, Cctrl *cc, Ast *fn) {
    AoStr *file = cctrlLookUpFile(cc, fn->file_id);
    if (file == NULL || jit->sym_files == NULL) return;
    const char *name = asmNormaliseFunctionName(cc, fn->fname);
    if (mapGet(jit->sym_files, (void *)name)) return;
    mapAdd(jit->sym_files, strdup(name), strdup(file->data));
}

/* Site `site`'s number in *sites (from 1), its outer sites first */
static int jitSiteNum(Vec *sites, IrInlineSite *site) {
    if (!site) return 0;
    for (u64 i = 0; i < sites->size; ++i) {
        if (sites->entries[i] == site) return (int)i + 1;
    }
    jitSiteNum(sites, site->outer);
    vecPush(sites, site);
    return (int)sites->size;
}

/* Record the function just emitted from enc offset `start`, with the
 * pending lines from `from` on, for the crash report: the records
 * asmLinesEmit writes for a compiled program, in memory. The inline
 * sites live in the IR arena, so they are copied now. */
static void jitNoteFunction(KtmJit *jit, Ast *fn, size_t start, u32 from) {
    Cctrl *cc = jit->cc;
    if (jit->n_fns == jit->cap_fns) {
        jit->cap_fns = jit->cap_fns ? jit->cap_fns * 2 : 64;
        jit->fns = realloc(jit->fns, jit->cap_fns * sizeof(KtmJitFn));
    }
    KtmJitFn *f = &jit->fns[jit->n_fns++];
    memset(f, 0, sizeof(*f));
    f->addr = start;
    f->size = jit->enc.len - start;
    f->name = strdup(asmLinesName(cc, fn->fname));
    AoStr *file = cctrlLookUpFile(cc, fn->file_id);
    f->file = file ? strdup(asmLinesPath(cc, file->data)) : NULL;
    f->omit_frame = jit->fn_omit_frame;

    u32 n = jit->n_pending - from + 1;
    f->lines = malloc(n * 2 * sizeof(int32_t));
    Vec *sites = vecNew(&vec_unsigned_long_type);
    if (fn->line) {
        f->lines[0] = 0;
        f->lines[1] = fn->line > 0xFFFFF ? 0xFFFFF : fn->line;
        f->count = 1;
    }
    for (u32 i = from; i < jit->n_pending; ++i) {
        KtmJitLine *l = &jit->pending_lines[i];
        int32_t *t = &f->lines[2 * f->count++];
        t[0] = (int32_t)(l->addr - start);
        t[1] = (l->line > 0xFFFFF ? 0xFFFFF : l->line) |
               (jitSiteNum(sites, l->site) << 20);
    }
    f->n_sites = (int)sites->size;
    f->sites = calloc(sites->size + 1, sizeof(KtmJitSite));
    for (u64 i = 0; i < sites->size; ++i) {
        IrInlineSite *site = sites->entries[i];
        f->sites[i].name = strdup(asmLinesName(cc, site->callee->fname));
        u32 fid = site->callee->file_id;
        AoStr *sfile = fid && fid != fn->file_id ? cctrlLookUpFile(cc, fid) : NULL;
        f->sites[i].file = sfile ? strdup(asmLinesPath(cc, sfile->data)) : NULL;
        f->sites[i].line = site->call_line;
        f->sites[i].outer = jitSiteNum(sites, site->outer);
    }
    vecRelease(sites);
}

/* rt.crash's CrashFn and CrashSite: their addresses are offsets from
 * where each is stored */
typedef struct KtmCrashFn {
    int64_t start;
    int32_t size, name, file, lines, sites, count, flags, pad;
} KtmCrashFn;

typedef struct KtmCrashSite {
    int32_t name, line, outer, file;
} KtmCrashSite;

/* The offset from `field` to `to` */
static int32_t jitCrashRel(void *field, void *to) {
    return to ? (int32_t)((char *)to - (char *)field) : 0;
}

/* Bump allocation in the block jitCrashTable builds, 8-aligned */
static char *jitCrashTake(char **at, size_t n) {
    char *p = *at;
    *at += (n + 7) & ~(size_t)7;
    return p;
}

/* Every JIT'd function's record, in one block so its offsets are short */
static KtmCrashFn *jitCrashTable(KtmJit *jit) {
    size_t total = jit->n_fns * sizeof(KtmCrashFn);
    for (u32 i = 0; i < jit->n_fns; ++i) {
        KtmJitFn *f = &jit->fns[i];
        total += ((f->count * 2 * sizeof(int32_t)) + 7) & ~(size_t)7;
        total += ((f->n_sites * sizeof(KtmCrashSite)) + 7) & ~(size_t)7;
        total += strlen(f->name) + 8;
        if (f->file) total += strlen(f->file) + 8;
        for (int k = 0; k < f->n_sites; ++k) {
            total += strlen(f->sites[k].name) + 8;
            if (f->sites[k].file) total += strlen(f->sites[k].file) + 8;
        }
    }
    char *block = calloc(1, total), *at = block;
    if (!block) return NULL;
    KtmCrashFn *t = (KtmCrashFn *)jitCrashTake(&at, jit->n_fns * sizeof(KtmCrashFn));
    for (u32 i = 0; i < jit->n_fns; ++i) {
        KtmJitFn *f = &jit->fns[i];
        KtmCrashFn *r = &t[i];
        r->start = (int64_t)(f->addr - (uintptr_t)&r->start);
        r->size = (int32_t)f->size;
        char *name = jitCrashTake(&at, strlen(f->name) + 1);
        strcpy(name, f->name);
        r->name = jitCrashRel(&r->name, name);
        if (f->file) {
            char *file = jitCrashTake(&at, strlen(f->file) + 1);
            strcpy(file, f->file);
            r->file = jitCrashRel(&r->file, file);
        }
        int32_t *lines = (int32_t *)jitCrashTake(&at, f->count * 2 * sizeof(int32_t));
        memcpy(lines, f->lines, f->count * 2 * sizeof(int32_t));
        r->lines = jitCrashRel(&r->lines, lines);
        if (f->n_sites) {
            KtmCrashSite *sites = (KtmCrashSite *)jitCrashTake(&at,
                    f->n_sites * sizeof(KtmCrashSite));
            for (int k = 0; k < f->n_sites; ++k) {
                char *sn = jitCrashTake(&at, strlen(f->sites[k].name) + 1);
                strcpy(sn, f->sites[k].name);
                sites[k].name = jitCrashRel(&sites[k].name, sn);
                sites[k].line = f->sites[k].line;
                sites[k].outer = f->sites[k].outer;
                if (f->sites[k].file) {
                    char *sf = jitCrashTake(&at, strlen(f->sites[k].file) + 1);
                    strcpy(sf, f->sites[k].file);
                    sites[k].file = jitCrashRel(&sites[k].file, sf);
                }
            }
            r->sites = jitCrashRel(&r->sites, sites);
        }
        r->count = f->count;
        r->flags = f->omit_frame ? 1 : 0;
    }
    return t;
}

/* rt.crash's function `name`, JIT'd with the program */
static void *jitCrashFunction(KtmJit *jit, const char *name) {
    char *qual = mprintf("rt.crash.%s", name);
    AoStr sym = { .data = astModuleMangle(qual, strlen(qual)), .len = 0, .capacity = 0 };
    sym.len = strlen(sym.data);
    return jitResolveSymbol(jit, asmNormaliseFunctionName(jit->cc, &sym));
}

/* Hand every JIT'd function to the crash handler, as a compiled
 * program's constructor does (asmLinesEmit), and libktm's records:
 * the shared library's own, which it says where they are */
static void jitInstallCrashReport(KtmJit *jit) {
    void (*install)(KtmCrashFn *, KtmCrashFn *) =
            (void (*)(KtmCrashFn *, KtmCrashFn *))jitCrashFunction(jit, "__ktmCrashInstall");
    void (*add)(KtmCrashFn *, KtmCrashFn *) =
            (void (*)(KtmCrashFn *, KtmCrashFn *))jitCrashFunction(jit, "__ktmCrashAdd");
    if (!install || !jit->n_fns) return;
    KtmCrashFn *t = jitCrashTable(jit);
    if (!t) return;
    install(t, t + jit->n_fns);
    KtmCrashFn **lo = dlsym(RTLD_DEFAULT, "ktm_lines_lo");
    KtmCrashFn **hi = dlsym(RTLD_DEFAULT, "ktm_lines_hi");
    if (add && lo && hi) add(*lo, *hi);
}

static void jitChunkFnAdd(KtmJit *jit, const char *name) {
    if (!mapHas(jit->chunk_fns, (void *)name)) {
        mapAdd(jit->chunk_fns, strdup(name), (void *)(uintptr_t)1);
    }
}

int ktmJitCompileChunk(KtmJit *jit, Ast *extra_fn) {
    Cctrl *cc = jit->cc;

    /* Pick up any `#link` libraries recorded since the last chunk
     * (REPL inputs can add them at any point). */
    jitLoadSharedObjects(cc);

    /* Reset the per-chunk encoder + internal-function set. Pending
     * line notes from a failed previous chunk die with it. */
    asm_enc_free(&jit->enc);
    asm_enc_init(&jit->enc);
    mapClear(jit->chunk_fns);
    jit->n_pending = 0;

    /* First unconsumed nodes. The cursors advance immediately: even if
     * this chunk fails, the next call must not recompile its ASTs. */
    List *ast_from = jit->ast_cursor->next;
    List *asm_from = jit->asm_cursor ? jit->asm_cursor->next : NULL;
    jit->ast_cursor = cc->ast_list->prev;
    if (cc->asm_blocks) jit->asm_cursor = cc->asm_blocks->prev;

    if (jitAllocateGlobals(jit, ast_from) != 0) return -1;

    /* Pre-register every internal function name so emit-time code
     * (IR_LEA on a function, IR_CALL to another function in this
     * chunk) can route through label fixups rather than dlsym (which
     * doesn't see this-chunk JIT code). */
    for (List *it = ast_from; it != cc->ast_list; it = it->next) {
        Ast *ast = it->value;
        if (ast->kind != AST_FUNC) continue;
        jitChunkFnAdd(jit, asmNormaliseFunctionName(cc, ast->fname));
    }
    if (extra_fn) {
        jitChunkFnAdd(jit, asmNormaliseFunctionName(cc, extra_fn->fname));
    }

    /* `asm {}` functions are internal too: register both the raw label
     * name and its platform-mangled form so calls and IR_LEA route
     * through label fixups (the finalize matcher is underscore-
     * tolerant for the raw/mangled difference). */
    if (asm_from) {
        for (List *it = asm_from; it != cc->asm_blocks; it = it->next) {
            Ast *asm_block = (Ast *)it->value;
            if (!asm_block->funcs) continue;
            for (List *fl = asm_block->funcs->next; fl != asm_block->funcs;
                 fl = fl->next)
            {
                Ast *fn = (Ast *)fl->value;
                jitChunkFnAdd(jit, fn->asmfname->data);
                jitChunkFnAdd(jit, asmNormaliseFunctionName(cc, fn->asmfname));
            }
        }
    }

    IrCtx *ir_ctx = irCtxNew(cc);
    u32 first_fn = jit->n_fns;
    for (List *it = ast_from; it != cc->ast_list; it = it->next) {
        Ast *ast = it->value;
        if (ast->kind != AST_FUNC) continue;
        jitNoteSymFile(jit, cc, ast);
        u32 from = jit->n_pending;
        size_t start = jit->enc.len;
        if (jit->backend->compile_function(jit, ast, ir_ctx) != 0) return -1;
        jitNoteFunction(jit, ast, start, from);
    }
    if (extra_fn) {
        jitNoteSymFile(jit, cc, extra_fn);
        u32 from = jit->n_pending;
        size_t start = jit->enc.len;
        if (jit->backend->compile_function(jit, extra_fn, ir_ctx) != 0)
            return -1;
        jitNoteFunction(jit, extra_fn, start, from);
    }

    /* `asm {}` function bodies: define each public label, then splice
     * the libtasm-encoded bytes in. */
    if (asm_from) {
        for (List *it = asm_from; it != cc->asm_blocks; it = it->next) {
            Ast *asm_block = (Ast *)it->value;
            if (!asm_block->funcs) continue;
            for (List *fl = asm_block->funcs->next; fl != asm_block->funcs;
                 fl = fl->next)
            {
                Ast *fn = (Ast *)fl->value;
                asm_define_label(&jit->enc, -1, fn->asmfname->data);
                if (ktmJitAssembleText(jit, fn->body->asm_stmt, 0) != 0)
                    return -1;
            }
        }
    }

    /* Nothing emitted (e.g. the input only declared a class or a
     * prototype): success, no mapping needed. */
    if (jit->enc.len == 0) {
        jitResolvePtrFixups(jit);
        return 0;
    }

    /* Dev hex dump of pre-finalize bytes. Triggered by KTM_JIT_DUMP=1. */
    if (getenv("KTM_JIT_DUMP")) {
        fprintf(stderr, "=== JIT encoded bytes (len=%zu) ===\n", jit->enc.len);
        for (size_t i = 0; i < jit->enc.len; ++i) {
            fprintf(stderr, "%02x%s", jit->enc.bytes[i],
                    (i & 15) == 15 ? "\n" : " ");
        }
        fputc('\n', stderr);
        fprintf(stderr, "=== labels (%d) ===\n", jit->enc.n_labels);
        for (int i = 0; i < jit->enc.n_labels; ++i) {
            AsmLabelDef *L = &jit->enc.labels[i];
            fprintf(stderr, "  %04zx %s%d %s\n", L->byte_offset,
                    L->name ? "" : "L", L->local_num,
                    L->name ? L->name : "");
        }
    }

    /* an unresolved global reported while emitting */
    if (jit->enc.errors) return -1;
    AsmJitCode *code = calloc(1, sizeof *code);
    if (asm_jit_finalize(&jit->enc, jitResolveSymbol, jit, code) != 0) {
        free(code);
        return -1;
    }
    listAppend(jit->chunks, code);

    /* this chunk's functions: offsets become addresses */
    for (u32 i = first_fn; i < jit->n_fns; ++i) {
        jit->fns[i].addr += (uintptr_t)code->code;
    }

    /* Rebase this chunk's pending (enc offset, line) notes onto the
     * finalized mapping and publish them for pc->line lookups. */
    if (jit->n_pending) {
        if (jit->n_lines + jit->n_pending > jit->cap_lines) {
            jit->cap_lines = jit->cap_lines ? jit->cap_lines * 2 : 128;
            while (jit->cap_lines < jit->n_lines + jit->n_pending)
                jit->cap_lines *= 2;
            jit->lines = realloc(jit->lines,
                    jit->cap_lines * sizeof(KtmJitLine));
        }
        for (u32 i = 0; i < jit->n_pending; ++i) {
            jit->lines[jit->n_lines].addr =
                (uintptr_t)code->code + jit->pending_lines[i].addr;
            jit->lines[jit->n_lines].line = jit->pending_lines[i].line;
            jit->lines[jit->n_lines].own_line = jit->pending_lines[i].own_line;
            jit->lines[jit->n_lines].site = NULL;  /* its arena is gone */
            /* KTM_DEBUG_LINES=1 dumps the table as chunks finalize -
             * pairs with `replDisasm` output when debugging attribution. */
            if (getenv("KTM_DEBUG_LINES"))
                fprintf(stderr, "line-table: %p line %d\n",
                        (void *)jit->lines[jit->n_lines].addr,
                        jit->lines[jit->n_lines].line);
            jit->n_lines++;
        }
        jit->n_pending = 0;
    }

    /* Each public label now points at (code base + label byte_offset).
     * Register in `symbols` (ktmJitLookup) and in `host_symbols` so
     * later chunks' emit-time addressing and finalize-time resolver
     * both see it. A redefinition overwrites: future chunks bind to
     * the newest body, already-patched code keeps the old one. */
    for (int i = 0; i < jit->enc.n_labels; ++i) {
        AsmLabelDef *L = &jit->enc.labels[i];
        if (!L->name) continue;
        void *addr = (uint8_t *)code->code + L->byte_offset;
        mapAdd(jit->symbols, strdup(L->name), addr);
        mapAdd(jit->host_symbols, strdup(L->name), addr);
    }
    jitResolvePtrFixups(jit);
    return 0;
}

KtmJit *ktmJitCompile(Cctrl *cc, const KtmJitBackend *backend) {
    KtmJit *jit = ktmJitNew(cc, backend);
    if (!jit) return NULL;

    /* -Memsafe: install the tracking allocator before the chunk
     * compiles, so every call site binds to it at finalize. */
    if (cc->flags & CCTRL_MEMSAFE)
        memsafeInit(jit);

    /* ktm "scripting" mode: statements at file scope land in
     * cc->initalisers and get wrapped into a synthetic `main`. When
     * this happens the user-supplied Main is renamed to MainFn (see
     * asmNormaliseFunctionName). We have to apply the same wrapping
     * before compiling so the entry point we hand to `ktmJitRunMain`
     * actually runs the init statements. */
    Ast *synth_main = asmBuildInitialiserMain(cc);

    if (ktmJitCompileChunk(jit, synth_main) != 0) {
        ktmJitFree(jit);
        return NULL;
    }
    return jit;
}

/* ---------------- public accessors ---------------- */

void *ktmJitLookup(KtmJit *jit, const char *name) {
    if (!jit || !name) return NULL;
    void *p = mapGet(jit->symbols, (void *)name);
    if (p) return p;
    /* Try the underscore-prefixed mangled form too (macOS convention). */
    if (name[0] != '_') {
        char buf[256];
        snprintf(buf, sizeof buf, "_%s", name);
        return mapGet(jit->symbols, (void *)buf);
    }
    return NULL;
}

AsmJitCode *ktmJitFindChunk(KtmJit *jit, void *pc) {
    if (!jit || !pc) return NULL;
    listForEach(jit->chunks) {
        AsmJitCode *chunk = (AsmJitCode *)it->value;
        uint8_t *base = (uint8_t *)chunk->code;
        if ((uint8_t *)pc >= base &&
            (uint8_t *)pc < base + chunk->size + chunk->veneer_size)
        {
            return chunk;
        }
    }
    return NULL;
}

const char *ktmJitFindSymbolForAddr(KtmJit *jit, void *pc, size_t *off_out) {
    if (off_out) *off_out = 0;
    AsmJitCode *chunk = ktmJitFindChunk(jit, pc);
    if (chunk == NULL) return NULL;
    /* Veneers belong to the chunk, not to whichever function happens
     * to be emitted last - don't attribute them. */
    if ((uint8_t *)pc >= (uint8_t *)chunk->code + chunk->size) return NULL;

    const char *best = NULL;
    uint8_t *best_addr = NULL;
    MapIter mi;
    mapIterInit(jit->symbols, &mi);
    while (mapIterNext(&mi)) {
        uint8_t *sym = (uint8_t *)mi.node->value;
        if (sym <= (uint8_t *)pc && sym >= (uint8_t *)chunk->code &&
            (best_addr == NULL || sym > best_addr))
        {
            best_addr = sym;
            best = (const char *)mi.node->key;
        }
    }
    if (best && off_out) *off_out = (size_t)((uint8_t *)pc - best_addr);
    return best;
}

const char *ktmJitFindFileForSymbol(KtmJit *jit, const char *sym) {
    if (!jit || !jit->sym_files || !sym) return NULL;
    const char *f = mapGet(jit->sym_files, (void *)sym);
    if (f == NULL && sym[0] == '_') f = mapGet(jit->sym_files, (void *)(sym + 1));
    return f;
}

void ktmJitNoteLine(KtmJit *jit, int line, IrInlineSite *site) {
    if (!jit || line <= 0) return;
    /* Collapse runs: only the line CHANGES matter. */
    if (jit->n_pending &&
        jit->pending_lines[jit->n_pending - 1].line == line &&
        jit->pending_lines[jit->n_pending - 1].site == site)
    {
        return;
    }
    if (jit->n_pending == jit->cap_pending) {
        jit->cap_pending = jit->cap_pending ? jit->cap_pending * 2 : 64;
        jit->pending_lines = realloc(jit->pending_lines,
                jit->cap_pending * sizeof(KtmJitLine));
    }
    /* a line inside a call inlined from another file is that file's:
     * the function's own line is the call's, outward until one is */
    int own = line;
    for (IrInlineSite *s = site; s; s = s->outer) {
        if (s->callee->file_id && s->callee->file_id != jit->fn_file_id &&
            s->call_line > 0) {
            own = s->call_line;
        }
    }
    jit->pending_lines[jit->n_pending].addr = (uintptr_t)jit->enc.len;
    jit->pending_lines[jit->n_pending].line = line;
    jit->pending_lines[jit->n_pending].own_line = own;
    jit->pending_lines[jit->n_pending].site = site;
    jit->n_pending++;
}

int ktmJitFindLineForAddr(KtmJit *jit, void *pc) {
    if (!jit || !jit->lines) return 0;
    AsmJitCode *chunk = ktmJitFindChunk(jit, pc);
    if (chunk == NULL) return 0;
    uintptr_t lo = (uintptr_t)chunk->code;
    uintptr_t hi = lo + chunk->size; /* veneers carry no lines */
    uintptr_t p = (uintptr_t)pc;
    if (p >= hi) return 0;
    int best_line = 0;
    uintptr_t best_addr = 0;
    for (u32 i = 0; i < jit->n_lines; ++i) {
        KtmJitLine *e = &jit->lines[i];
        if (e->addr < lo || e->addr >= hi) continue; /* other chunk */
        if (e->addr <= p && e->addr >= best_addr) {
            best_addr = e->addr;
            best_line = e->own_line;
        }
    }
    return best_line;
}

void ktmJitDefineSymbol(KtmJit *jit, const char *name, void *addr) {
    if (!jit || !name) return;
    /* Register BOTH spellings: ktm-compiled call sites arrive
     * platform-mangled (`_free` on Mach-O) while `asm {}` blocks emit
     * the symbol verbatim (`CALL malloc` -> `malloc`). The resolver's
     * host_symbols lookup is exact, so one spelling would miss the
     * other's callers. */
    mapAdd(jit->host_symbols, strdup(name), addr);
    if (name[0] != '_') {
        char buf[64];
        snprintf(buf, sizeof(buf), "_%s", name);
        mapAdd(jit->host_symbols, strdup(buf), addr);
    } else {
        mapAdd(jit->host_symbols, strdup(name + 1), addr);
    }
}

union JitMainFunction {
    void *obj;
    int (*main_fn)(int, char **);
};

int ktmJitRunMain(KtmJit *jit, int argc, char **argv) {
    union JitMainFunction entry;
    entry.obj = ktmJitLookup(jit, "main");
    if (!entry.obj) entry.obj = ktmJitLookup(jit, "_main");
    /* No main: say so, rather than exiting 255 with nothing printed. */
    if (!entry.obj) {
        fprintf(stderr, "ktm: %s: nothing to run: no main function\n",
                argc > 0 ? argv[0] : "-jit");
        return 1;
    }
    jitInstallCrashReport(jit);
    return entry.main_fn(argc, argv);
}

void ktmJitFree(KtmJit *jit) {
    if (!jit) return;
    free(jit->lines);
    free(jit->pending_lines);
    asm_enc_free(&jit->enc);
    if (jit->chunks) {
        listForEach(jit->chunks) {
            AsmJitCode *code = (AsmJitCode *)it->value;
            asm_jit_free(code);
            free(code);
        }
        listRelease(jit->chunks, NULL);
    }
    if (jit->globals_arenas) listRelease(jit->globals_arenas, free);
    if (jit->string_arenas) listRelease(jit->string_arenas, jitStringsFree);
    if (jit->symbols) mapRelease(jit->symbols);
    if (jit->host_symbols) mapRelease(jit->host_symbols);
    if (jit->sym_files) mapRelease(jit->sym_files);
    if (jit->chunk_fns) mapRelease(jit->chunk_fns);
    if (jit->block_local) mapRelease(jit->block_local);
    if (jit->epi_local) mapRelease(jit->epi_local);
    free(jit);
}
