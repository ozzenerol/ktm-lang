module crash;
import libc;

/* ---- crash reports ----
 * A program that faults (a null pointer, a stack overflow, a division by
 * zero) prints what happened and where before it dies:
 *
 *   crash: null pointer dereference (address 0x0)
 *     at bookmark.ktm:14 in bookmark.create
 *     called from net/http/mux.ktm:421 in net.http.muxServe
 *     ...
 *
 * The compiler gives every function a CrashFn record in the ktm_lines
 * section (asmLinesEmit) and makes a program call __ktmCrashInstall
 * with the section's bounds before main. This module is compiled into
 * every program (lexPushRuntime), not into libktm, and needs nothing
 * but libc: a program that uses no library links none for it. The
 * handler runs on a stack of its own, so a stack overflow can be
 * reported too, and only writes(2) from static buffers. When it returns
 * the signal's default action is back, the faulting instruction runs
 * again and the program dies of the signal as before (exit status
 * 128 + signal in a shell). */

/* The records asmLinesEmit writes. Their addresses are offsets from
 * where each is stored (crashAt), so they need no relocating. */

/* A call the inliner merged into the function: the callee's name, the
 * line of the call, the site that call is in (0: the function's own
 * code) and the callee's file (0: the function's) */
struct CrashSite {
    i32 name;
    i32 line;
    i32 outer;
    i32 file;
};

struct CrashFn {
    i64 start;
    i32 size;
    i32 name;
    i32 file;       /* 0: none */
    i32 lines;      /* count pairs: code offset, line | site << 20 */
    i32 sites;
    i32 count;
    i32 flags;      /* 1: no frame, the return address is in lr / at [sp] */
    i32 pad;
};

/* What the offset at `field` points at, null for 0 */
static u8 *crashAt(i32 *field) {
    if (!*field) { return null; }
    return (u8 *)field + *field;
}

static u64 crashStart(CrashFn *f) {
    return (u64)&f.start + f.start;
}

#ifdef IS_MACOS
const i64 CRASH_SA_FLAGS_AT = 12;
const i32 CRASH_SA_SIGINFO = 0x40;
const i32 CRASH_SA_ONSTACK = 0x1;
const i32 CRASH_SA_RESETHAND = 0x4;
const i32 CRASH_SIGBUS = 10;
const i32 CRASH_FPE_INTDIV = 7;
const i64 CRASH_SI_ADDR_AT = 24;
#else
const i64 CRASH_SA_FLAGS_AT = 136;
const i32 CRASH_SA_SIGINFO = 0x4;
const i32 CRASH_SA_ONSTACK = 0x08000000;
const i32 CRASH_SA_RESETHAND = 0x80000000;
const i32 CRASH_SIGBUS = 7;
const i32 CRASH_FPE_INTDIV = 1;
const i64 CRASH_SI_ADDR_AT = 16;
#endif

const i32 CRASH_SIGILL = 4;
const i32 CRASH_SIGTRAP = 5;
const i32 CRASH_SIGFPE = 8;
/* `brk #0xd10` (A64_BRK_DIVZERO): arm64's divide gives 0 for a zero
 * divisor, so the compiler tests it and runs this instead */
const u32 CRASH_BRK_DIVZERO = 0xd4200000 | (0xd10 << 5);
/* `brk #0xd11` (A64_BRK_DIVOVF): the smallest i64 divided by -1 */
const u32 CRASH_BRK_DIVOVF = 0xd4200000 | (0xd11 << 5);

/* What a division fault was (crashDivKind) */
const i32 CRASH_DIV_NONE = 0;
const i32 CRASH_DIV_ZERO = 1;
const i32 CRASH_DIV_OVERFLOW = 2;
const i32 CRASH_SIGSEGV = 11;

const i64 CRASH_STACK_BYTES = 65536;
const i64 CRASH_MAX_LINES = 24;    /* frames shown before "... more" */

/* The record tables: the program's, and in the JIT its own and
 * libktm's (each a [lo, hi) range) */
const i64 CRASH_TABLES = 8;
static CrashFn *crashLo[CRASH_TABLES], *crashHi[CRASH_TABLES];
static i64 crashTables = 0;
static bool crashInstalled = false;
static u8 crashStack[CRASH_STACK_BYTES];
static u8 crashOut[512];
static i64 crashLen = 0;

static void crashFlush() {
    if (crashLen) { libc.write(2, crashOut, crashLen); }
    crashLen = 0;
}

static void crashPut(u8 *s) {
    if (!s) { return; }
    while (*s) {
        if (crashLen == sizeof(crashOut)) { crashFlush(); }
        crashOut[crashLen++] = *s++;
    }
}

static void crashPutDec(u64 v) {
    u8 d[24];
    i64 n = 23;
    d[n] = 0;
    do {
        d[--n] = '0' + v % 10;
        v /= 10;
    } while (v);
    crashPut(&d[n]);
}

static void crashPutHex(u64 v) {
    u8 d[24];
    i64 n = 23;
    d[n] = 0;
    do {
        d[--n] = "0123456789abcdef"[v & 15];
        v >>= 4;
    } while (v);
    d[--n] = 'x';
    d[--n] = '0';
    crashPut(&d[n]);
}

static bool crashSame(u8 *a, u8 *b) {
    while (*a && *a == *b) {
        a++;
        b++;
    }
    return *a == *b;
}

/* The ktm function pc is in, null for C code */
static CrashFn *crashFind(u64 pc) {
    CrashFn *f;
    i64 t;
    for (t = 0; t < crashTables; t++) {
        for (f = crashLo[t]; f < crashHi[t]; f++) {
            u64 start = crashStart(f);
            if (pc >= start && pc < start + f.size) { return f; }
        }
    }
    return null;
}

/* The source line of pc in f, the last that starts at or before it,
 * and in *site the inlined call it is in */
static i32 crashLine(CrashFn *f, u64 pc, i32 *site) {
    i32 line = 0, i;
    u64 off = pc - crashStart(f);
    i32 *lines = (i32 *)crashAt(&f.lines);
    *site = 0;
    for (i = 0; i < f.count; i++) {
        if ((u64)lines[2 * i] > off) { break; }
        line = lines[2 * i + 1] & 0xFFFFF;
        *site = (u32)lines[2 * i + 1] >> 20;
    }
    return line;
}

/* The frame being printed, and how often it repeated (a recursion) */
static u64 crashPrevPc = 0;
static i64 crashRepeat = 0, crashShown = 0, crashHidden = 0;
static bool crashInC = false, crashAtMain = false;
static bool crashNextAt = false;   /* the next frame shown is "at" */

static void crashEndRepeat() {
    if (crashRepeat > 1) {
        crashPut(" (");
        crashPutDec(crashRepeat);
        crashPut(" times)");
    }
    if (crashPrevPc) { crashPut("\n"); }
    crashFlush();
    crashRepeat = 0;
}

static void crashPlace(u8 *what, u8 *file, i32 line, u8 *name) {
    crashPut(what);
    if (file) {
        crashPut(file);
        crashPut(":");
        crashPutDec(line);
        crashPut(" in ");
    }
    crashPut(name);
}

/* One frame: pc where it is, `first` for the faulting one; a return
 * address is past its call, so its line is the one at pc - 1. */
static void crashFrame(u64 pc, bool first) {
    CrashFn *f = crashFind(first ? pc : pc - 1);
    if (!f) {
        /* shown when a ktm frame follows: the C code a program starts
         * in, or a thread or coroutine, isn't worth a line */
        if (first) {
            crashPut("  in C code\n");
            crashFlush();
            crashShown++;
        } else if (crashPrevPc) {
            crashInC = true;
        }
        return;
    }
    if (crashInC) {
        crashEndRepeat();
        crashPrevPc = 0;
        if (crashShown < CRASH_MAX_LINES) {
            crashPut("  called from C code\n");
            crashShown++;
        }
        crashInC = false;
    }
    /* below main is the C library starting the program */
    u8 *name = crashAt(&f.name), *file = crashAt(&f.file);
    crashAtMain = name[0] == 'm' && name[1] == 'a' && name[2] == 'i' &&
                  name[3] == 'n' && name[4] == 0;
    i32 site;
    i32 line = crashLine(f, first ? pc : pc - 1, &site);
    if (pc == crashPrevPc) {
        crashRepeat++;
        return;
    }
    crashEndRepeat();
    crashPrevPc = 0;
    if (crashShown >= CRASH_MAX_LINES) {
        crashHidden++;
        return;
    }
    crashShown++;
    crashPrevPc = pc;
    crashRepeat = 1;
    if (crashNextAt) {
        first = true;
        crashNextAt = false;
    }
    /* the calls the inliner merged in, innermost first: a line in a
     * site is in its callee's file */
    while (site) {
        CrashSite *c = (CrashSite *)crashAt(&f.sites) + (site - 1);
        u8 *siteFile = crashAt(&c.file);
        crashPlace(first ? "  at " : "  called from ", siteFile ? siteFile : file, line,
                   crashAt(&c.name));
        crashPut(" (inlined)\n");
        first = false;
        line = c.line;
        site = c.outer;
    }
    crashPlace(first ? "  at " : "  called from ", file, line, name);
}

/* Can the 16 bytes of a frame record at p be read? A frame pointer
 * from C code can be anything, and a fault in here ends the report. */
static bool crashReadable(u64 p) {
    u8 vec[2];
    u64 page = p & ~(u64)4095;
    return libc.mincore((void *)page, (p + 16 > page + 4096) ? 8192 : 4096, vec) == 0;
}

/* x86_64 C code keeps no frame pointer we can trust: the first word on
 * the stack that is an address in ktm code is the return into it. Each
 * page is checked when the scan reaches it: the stack can end less than
 * a page above sp (the main thread's, with a small environment). */
static u64 crashScanStack(u64 sp) {
    i64 i;
    u64 mapped = 0;     /* the end of the pages checked so far */
    sp &= ~(u64)7;
    for (i = 0; i < 512; i++) {
        u64 p = sp + 8 * i;
        if (p + 8 > mapped) {
            u8 vec[1];
            u64 page = p & ~(u64)4095;
            if (libc.mincore((void *)page, 4096, vec) != 0) { return 0; }
            mapped = page + 4096;
        }
        u64 w = *(u64 *)p;
        if (crashFind(w - 1)) { return w; }
    }
    return 0;
}

static void crashHeadline(i32 sig, u8 *info, u64 sp, i32 div) {
    u64 addr = *(u64 *)(info + CRASH_SI_ADDR_AT);
    i32 code = *(i32 *)(info + 8);
    crashPut("crash: ");
    if (div == CRASH_DIV_ZERO) {
        crashPut("integer division by zero");
    } else if (div == CRASH_DIV_OVERFLOW) {
        crashPut("integer overflow in division (-9223372036854775808 / -1)");
    } else if (sig == CRASH_SIGSEGV || sig == CRASH_SIGBUS) {
        if (addr < 4096) {
            crashPut("null pointer dereference (address ");
            crashPutHex(addr);
            crashPut(")");
        } else if (addr + CRASH_STACK_BYTES >= sp && addr < sp + 4096) {
            crashPut("stack overflow: the calls below went too deep (a recursion that never ends?)");
        } else {
            crashPut("invalid memory access at address ");
            crashPutHex(addr);
            crashPut(" (memory that was freed, never allocated, or read-only)");
        }
    } else if (sig == CRASH_SIGFPE) {
        crashPut(code == CRASH_FPE_INTDIV ? "integer division by zero" : "arithmetic error");
    } else if (sig == CRASH_SIGILL) {
        crashPut("illegal instruction");
    } else {
        crashPut("signal ");
        crashPutDec(sig);
    }
    crashPut("\n");
}

/* A fault at pc in ktm code: a division by zero or one that overflows
 * (the smallest i64 divided by -1)? arm64 tests for both and runs a
 * brk (A64_BRK_DIVZERO, A64_BRK_DIVOVF); x86_64's IDIV traps for both,
 * and the compiler always divides by rcx: not 0, an overflow. */
static i32 crashDivKind(i32 sig, u8 *info, u64 pc, u64 rcx) {
#ifdef IS_ARM_64
    if (sig != CRASH_SIGTRAP) { return CRASH_DIV_NONE; }
    u32 insn = *(u32 *)pc;
    if (insn == CRASH_BRK_DIVZERO) { return CRASH_DIV_ZERO; }
    if (insn == CRASH_BRK_DIVOVF) { return CRASH_DIV_OVERFLOW; }
#else
    u8 *p = (u8 *)pc;
    /* idivq %rcx */
    if (sig == CRASH_SIGFPE && *(i32 *)(info + 8) == CRASH_FPE_INTDIV &&
        p[0] == 0x48 && p[1] == 0xf7 && p[2] == 0xf9) {
        return rcx ? CRASH_DIV_OVERFLOW : CRASH_DIV_ZERO;
    }
#endif
    return CRASH_DIV_NONE;
}

static void crashHandler(i32 sig, u8 *info, u8 *uc) {
    u64 pc, fp, sp, lr = 0, rcx = 0;
#ifdef IS_MACOS
    u8 *mc = *(u8 **)(uc + 48);
#ifdef IS_ARM_64
    fp = *(u64 *)(mc + 248);
    lr = *(u64 *)(mc + 256);
    sp = *(u64 *)(mc + 264);
    pc = *(u64 *)(mc + 272);
#else
    rcx = *(u64 *)(mc + 32);
    fp = *(u64 *)(mc + 64);
    sp = *(u64 *)(mc + 72);
    pc = *(u64 *)(mc + 144);
#endif
#else
#ifdef IS_ARM_64
    fp = *(u64 *)(uc + 416);
    lr = *(u64 *)(uc + 424);
    sp = *(u64 *)(uc + 432);
    pc = *(u64 *)(uc + 440);
#else
    fp = *(u64 *)(uc + 120);
    rcx = *(u64 *)(uc + 152);
    sp = *(u64 *)(uc + 160);
    pc = *(u64 *)(uc + 168);
#endif
#endif
    CrashFn *f = crashFind(pc);
    /* what the program printed comes first: unless the fault is inside
     * C code, which may hold stdio's lock */
    if (f) { libc.fflush(null); }
    /* only in ktm code */
    i32 div = f ? crashDivKind(sig, info, pc, rcx) : CRASH_DIV_NONE;
    crashHeadline(sig, info, sp, div);
    crashFlush();

    crashFrame(pc, true);
    u64 first = 0;
    if (!crashAtMain && (!f || (f.flags & 1))) {
#ifdef IS_ARM_64
        first = lr;
#else
        first = f ? *(u64 *)sp : crashScanStack(sp);
#endif
        if (first) { crashFrame(first, false); }
    }

    /* the frame records: [fp] the caller's fp, [fp + 8] the return
     * address, on x86_64 and arm64 alike */
    u64 prev = 0;
    i64 depth;
    if (crashAtMain) { fp = 0; }
    for (depth = 0; depth < 1000000 && fp; depth++) {
        if ((fp & 7) || (prev && fp <= prev) || !crashReadable(fp)) { break; }
        u64 ret = ((u64 *)fp)[1];
        if (!ret) { break; }
        if (!(depth == 0 && ret == first)) {
            crashFrame(ret, false);
            if (crashAtMain) { break; }
        }
        prev = fp;
        fp = ((u64 *)fp)[0];
    }
    crashEndRepeat();
    if (crashHidden) {
        crashPut("  ... ");
        crashPutDec(crashHidden);
        crashPut(" more\n");
        crashFlush();
    }
    /* die of a SIGFPE, as x86_64 does (exit status 136), not of the brk */
    if (div != CRASH_DIV_NONE && sig != CRASH_SIGFPE) {
        libc.signal(CRASH_SIGFPE, 0);
        libc.raise(CRASH_SIGFPE);
    }
}

/* Is s the name of a function of rt (not rt.crash): the throw's own
 * calls, left out of an uncaught error's trace */
static bool crashInRt(u8 *s) {
    return s[0] == 'r' && s[1] == 't' && s[2] == '.' &&
           !(s[3] == 'c' && s[4] == 'r' && s[5] == 'a' && s[6] == 's' &&
             s[7] == 'h' && s[8] == '.');
}

/* An uncaught error (rt.__ktmUncaught, with its frame pointer): the
 * calls that led to the throw, as a crash shows them */
static void crashUncaughtTrace(u64 fp) {
    u64 prev = 0;
    i64 depth;
    bool skipping = true;
    crashNextAt = true;
    for (depth = 0; depth < 1000000 && fp; depth++) {
        if ((fp & 7) || (prev && fp <= prev) || !crashReadable(fp)) { break; }
        u64 ret = ((u64 *)fp)[1];
        if (!ret) { break; }
        if (skipping) {
            CrashFn *f = crashFind(ret - 1);
            skipping = f && crashInRt(crashAt(&f.name));
        }
        if (!skipping) {
            crashFrame(ret, false);
            if (crashAtMain) { break; }
        }
        prev = fp;
        fp = ((u64 *)fp)[0];
    }
    crashEndRepeat();
    if (crashHidden) {
        crashPut("  ... ");
        crashPutDec(crashHidden);
        crashPut(" more\n");
        crashFlush();
    }
}

/* Set the calling thread's signal stack (a stack_t: ss_sp, then
 * ss_flags and ss_size, whose order differs, at 8 or 16) */
static void crashAltStack(u8 *stack, i32 flags) {
    u64 ss[3];
    ss[0] = (u64)stack;
#ifdef IS_MACOS
    ss[1] = CRASH_STACK_BYTES;
    ss[2] = flags;
#else
    ss[1] = flags;
    ss[2] = CRASH_STACK_BYTES;
#endif
    libc.sigaltstack(ss, null);
}

/* A forked child: macOS doesn't keep the thread's signal stack, and
 * the child's one thread may take the main thread's */
#ifdef IS_MACOS
static void crashAfterFork() {
    crashAltStack(crashStack, 0);
}
#endif

/* A table of records to look frames up in: libktm's own when it is a
 * shared library, as the JIT has it (ktm_lines_lo/hi, asmLinesEmit) */
pub void __ktmCrashAdd(CrashFn *lo, CrashFn *hi) {
    i64 t;
    if (lo >= hi) { return; }
    for (t = 0; t < crashTables; t++) {
        if (crashLo[t] == lo) { return; }
    }
    if (crashTables == CRASH_TABLES) { return; }
    crashLo[crashTables] = lo;
    crashHi[crashTables] = hi;
    crashTables++;
    /* libktm's records (the program's own when it is linked in): hand
     * rt the walker for uncaught errors */
    CrashFn *f;
    for (f = lo; f < hi; f++) {
        if (crashSame(crashAt(&f.name), "rt.__ktmUncaughtTraceSet")) {
            fn(fn(u64 fp) void trace) void set = (void *)crashStart(f);
            set(&crashUncaughtTrace);
            return;
        }
    }
}

/* Called before main (asmLinesEmit, or the JIT) with the program's
 * records: adds them and installs the handler. */
pub void __ktmCrashInstall(CrashFn *lo, CrashFn *hi) {
    __ktmCrashAdd(lo, hi);
    if (crashInstalled) { return; }
    crashInstalled = true;

    crashAltStack(crashStack, 0);   /* the main thread's */
#ifdef IS_MACOS
    /* (Linux keeps it; and glibc's pthread_atfork is only in its static
     * part, which the JIT can't call) */
    libc.pthread_atfork(null, null, (void *)&crashAfterFork);
#endif

    i64 act[20];
    i64 i;
    for (i = 0; i < 20; i++) { act[i] = 0; }
    act[0] = (i64)(&crashHandler);
    libc.sigemptyset((u8 *)act + 8);
    *(i32 *)((u8 *)act + CRASH_SA_FLAGS_AT) =
            CRASH_SA_SIGINFO | CRASH_SA_ONSTACK | CRASH_SA_RESETHAND;
    libc.sigaction(CRASH_SIGSEGV, act, null);
    libc.sigaction(CRASH_SIGBUS, act, null);
    libc.sigaction(CRASH_SIGFPE, act, null);
    libc.sigaction(CRASH_SIGILL, act, null);
#ifdef IS_ARM_64
    libc.sigaction(CRASH_SIGTRAP, act, null);   /* CRASH_BRK_DIVZERO */
#endif
}
