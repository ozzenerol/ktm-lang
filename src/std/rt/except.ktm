module rt;
import cstr, libc, mem, os;

/* strings.ktm */

/* Compiler reserves 256 bytes per CatchFrame. We split that into a
 * 240-byte jmp_buf storage region (setjmp owns the format; _setjmp
 * writes 192 bytes at most: macOS arm64), the thread state the frame was
 * pushed on, and the chain pointer the runtime walks. */
const i64 __JMP_BUF_BYTES__ = 240;

pub struct CatchFrame;
pub struct HCFs;

pub struct CatchFrame {
    i8 jb[__JMP_BUF_BYTES__];
    HCFs *fs;              /* its thread's state: leaving needs no Fs() */
    CatchFrame *prev;
};

pub struct HCFs {
    CatchFrame *catchTop;  /* head of the active-try-frames chain */
    u64 exceptCh;          /* code of the most recent throw */
    void *coroCur;           /* running Coroutine, null on the thread's own stack */
    void *coroRoot;          /* save slot for the thread's own stack (coroutines.ktm) */
    void *coroCache;         /* released coroutine stacks kept for reuse */
    i64 coroCached;        /* how many */
    Error error;            /* the error being thrown, until a catch takes it */
    void *coroLoop;          /* the thread's coroutine loop (coro.go), or null */
    fn(void *loop) void coroLoopFree;  /* frees it when the thread ends */
    u64 stackLo, stackHi;    /* the running coroutine's stack (coroutines.ktm) */
    u64 threadLo, threadHi;  /* the thread's own stack, 0 until looked up */
    u8 *crashStack;          /* the thread's signal stack (__ktmCrashThread) */
};

/* The "" of an Error without a message (or file): never freed. */
static u8 _err_none[8];

static bool ErrOwns(u8 *message) {
    return message && message != _err_none;
}

/* coroutines.ktm */

/* pthread_once_t is an int on Linux and a signed struct on macOS
 * (PTHREAD_ONCE_INIT is {0x30B1BCBA, {0}}); pthread_key_t is 4 bytes on
 * Linux and 8 on macOS, so 8 zeroed bytes hold either. */
#ifdef IS_MACOS
static i64 fsOnce[2] = {0x30B1BCBA, 0};
#else
static i64 fsOnce[2] = {0, 0};
#endif

static u64 fsKey = 0;

static void fsFree(void *p) {
    HCFs *fs = p;
    if (ErrOwns(fs.error.message)) { mem.free(fs.error.message); }
    if (fs.coroLoop) { fs.coroLoopFree(fs.coroLoop); }
    coroStacksFree(fs);
    crashThreadEnd(fs);
    libc.free(fs.coroRoot);
    libc.free(fs);
}

static void fsKeyCreate() {
    libc.pthread_key_create(&fsKey, &fsFree);
}

/* The calling thread's HCFs, created on its first use. */
pub HCFs *Fs() {
    libc.pthread_once(fsOnce, &fsKeyCreate);
    HCFs *fs = libc.pthread_getspecific(fsKey);
    if (!fs) {
        fs = libc.calloc(1, sizeof(HCFs));
        if (!fs) {
            printf("out of memory for the exception state\n");
            os.exit(1);
        }
        libc.pthread_setspecific(fsKey, fs);
    }
    return fs;
}

/* Push the frame onto the catch-stack. The setjmp call itself MUST
 * stay inline in the user function (it captures the caller's frame,
 * and longjmp to a returned function's frame is UB), so the chain
 * mutation is the only thing we delegate to a helper. */
pub void __ktmPushFrame(CatchFrame *f) {
    HCFs *fs = Fs();
    f.prev = fs.catchTop;
    f.fs = fs;
    fs.catchTop = f;
}

/* The frame's own fs, not Fs() (pthread_getspecific): a try or defer cost
 * 12 ns, now under 7. A coroutine that moves to another thread gets its
 * frames' fs rewritten when it is switched to (coroSwitch). */
pub void __ktmTryLeave(CatchFrame *f) {
    f.fs.catchTop = f.prev;
}

/* Make e (a struct copy) the thread's current error; the message moves
 * in, the one there before is freed. */
static void ErrSet(HCFs *fs, u64 code, u8 *message, u8 *file, i64 line) {
    if (ErrOwns(fs.error.message)) { mem.free(fs.error.message); }
    fs.error.code = code;
    fs.error.message = message ? message : _err_none;
    fs.error.file = file ? file : _err_none;
    fs.error.line = line;
    fs.exceptCh = code;
}

static void WriteErr(u8 *s) {
    i64 n = 0;
    while (s[n]) { n++; }
    libc.write(os.STDERR, s, n);
}

/* Report an error no try catches on stderr and exit with 1:
 *   uncaught error <code>: <message> (<file>:<line>)
 * A code that is a character constant whose bytes are all printable is
 * shown as text ('Late'), any other as a signed decimal number. */
static void __ktmUncaught(HCFs *fs) {
    u8 buf[64], digits[24];
    i64 len = 0, n = 0, i;
    u64 v = fs.error.code, u, c;
    bool text = v != 0;

    for (u = v; u; u >>= 8) {
        c = u & 0xFF;
        if (c < ' ' || c > '~') { text = false; }
    }
    if (text) {
        buf[len++] = '\'';
        for (u = v; u; u >>= 8) { buf[len++] = u & 0xFF; }
        buf[len++] = '\'';
    } else {
        if ((i64)v < 0) {
            buf[len++] = '-';
            u = -(i64)v;
        } else {
            u = v;
        }
        do {
            digits[n++] = '0' + u % 10;
            u /= 10;
        } while (u);
        for (i = n - 1; i >= 0; i--) { buf[len++] = digits[i]; }
    }
    buf[len] = 0;
    libc.fflush(null);  /* what the program printed comes first */
    WriteErr("uncaught error ");
    WriteErr(buf);
    if (fs.error.message && fs.error.message[0]) {
        WriteErr(": ");
        WriteErr(fs.error.message);
    }
    if (fs.error.file && fs.error.file[0]) {
        WriteErr(" (");
        WriteErr(fs.error.file);
        len = 0;
        n = 0;
        u = fs.error.line;
        buf[len++] = ':';
        do {
            digits[n++] = '0' + u % 10;
            u /= 10;
        } while (u);
        for (i = n - 1; i >= 0; i--) { buf[len++] = digits[i]; }
        buf[len++] = ')';
        buf[len] = 0;
        WriteErr(buf);
    }
    WriteErr("\n");
    os.exit(1);
}

static void ErrJump(HCFs *fs) {
    auto t = fs.catchTop;
    if (!t) { __ktmUncaught(fs); }
    libc._longjmp(t.jb, 1);
}

/* `throw(code)`: the compiler passes where the throw is. */
pub void __ktmThrowAt(u64 code, u8 *file, i64 line) {
    HCFs *fs = Fs();
    ErrSet(fs, code, null, file, line);
    ErrJump(fs);
}

/* `throw(code, fmt, ...)`: the compiler checks the call as one to
 * KTM_ThrowFmt, then calls this with where the throw is in front. */
pub void __ktmThrowFmtAt(u8 *file, i64 line, u64 code, u8 *fmt, ...args) {
    u8 *message = cstr.formatArgs(null, fmt, args);
    HCFs *fs = Fs();
    ErrSet(fs, code, message, file, line);
    ErrJump(fs);
}

pub void __ktmThrowFmt(u64 code, u8 *fmt, ...args) {
    u8 *message = cstr.formatArgs(null, fmt, args);
    HCFs *fs = Fs();
    ErrSet(fs, code, message, null, 0);
    ErrJump(fs);
}

/* `throw(e)`: the same error again, with a copy of its message (the
 * catch that holds e frees its own when it is left). */
pub void __ktmThrowError(Error *e) {
    u8 *message = ErrOwns(e.message) ? cstr.dup(e.message) : null;
    HCFs *fs = Fs();
    ErrSet(fs, e.code, message, e.file, e.line);
    ErrJump(fs);
}

/* A failed bounds check (the compiler calls these): `s[i]` with i not
 * below the length, `s[a:b]` not within 0 <= a <= b <= length */
pub void __ktmBoundsIndex(i64 index, i64 len, u8 *file, i64 line) {
    HCFs *fs = Fs();
    ErrSet(fs, ERR_BOUNDS, cstr.format("index %d out of range for length %d", index, len),
           file, line);
    ErrJump(fs);
}

pub void __ktmBoundsSlice(i64 lo, i64 hi, i64 len, u8 *file, i64 line) {
    HCFs *fs = Fs();
    ErrSet(fs, ERR_BOUNDS, cstr.format("slice %d:%d out of range for length %d", lo, hi, len),
           file, line);
    ErrJump(fs);
}

/* ---- stack limits ----
 * A function with a frame bigger than a page calls __ktmStackCheck first
 * (the compiler emits it), with the lowest address the frame would reach:
 * past the bottom of the stack it is a stack overflow, reported here,
 * instead of a frame that leaps over the guard page and writes into
 * whatever lies below. Nothing is touched, so a big buffer on a stack
 * costs only the pages actually used. */

#ifdef IS_MACOS
extern "c" void *pthread_get_stackaddr_np(void *th);
extern "c" u64 pthread_get_stacksize_np(void *th);
#else
extern "c" i32 pthread_getattr_np(void *th, void *attr);
extern "c" i32 pthread_attr_getstack(void *attr, void **addr, u64 *size);
extern "c" i32 pthread_attr_destroy(void *attr);
#endif
extern "c" void *pthread_self();
extern "c" void abort();

const u64 STACK_RESERVE = 65536;

/* The calling thread's own stack, looked up once */
static void stackThreadBounds(HCFs *fs) {
#ifdef IS_MACOS
    void *th = pthread_self();
    u64 hi = (u64)pthread_get_stackaddr_np(th);
    fs.threadLo = hi - pthread_get_stacksize_np(th);
    fs.threadHi = hi;
#else
    u8 attr[128];
    void *addr = null;
    u64 size = 0;
    if (pthread_getattr_np(pthread_self(), attr) == 0) {
        pthread_attr_getstack(attr, &addr, &size);
        pthread_attr_destroy(attr);
    }
    fs.threadLo = (u64)addr;
    fs.threadHi = (u64)addr + size;
#endif
    if (fs.threadHi == 0) {
        fs.threadHi = 1;                           /* unknown: never look again */
        return;
    }
    /* The main thread's stack grows on demand, and the kernel can stop
     * it a few pages short of what the C library reports; the reserve
     * also leaves the overflow report room to run. */
    fs.threadLo += STACK_RESERVE;
}

/* ---- signal stacks ----
 * A thread the stdlib starts (a thread pool's, a server's) gets a stack
 * for signal handlers, so the crash report (rt/crash) can tell of its
 * stack overflow too: a handler can't run on the stack that overflowed. */

const i64 CRASH_STACK_BYTES = 65536;

#ifdef IS_MACOS
const i32 CRASH_SS_DISABLE = 4;
#else
const i32 CRASH_SS_DISABLE = 2;
#endif

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

pub void __ktmCrashThread() {
    HCFs *fs = Fs();
    if (fs.crashStack) { return; }
    fs.crashStack = libc.malloc(CRASH_STACK_BYTES);
    if (fs.crashStack) { crashAltStack(fs.crashStack, 0); }
}

/* The thread ends (its HCFs is freed) */
static void crashThreadEnd(HCFs *fs) {
    if (!fs.crashStack) { return; }
    crashAltStack(null, CRASH_SS_DISABLE);
    libc.free(fs.crashStack);
    fs.crashStack = null;
}

pub void __ktmStackCheck(u64 low) {
    HCFs *fs = Fs();
    u64 lo, hi;
    if (fs.coroCur) {
        lo = fs.stackLo;
        hi = fs.stackHi;
    } else {
        if (!fs.threadHi) { stackThreadBounds(fs); }
        lo = fs.threadLo;
        hi = fs.threadHi;
    }
    u64 here = (u64)&low;
    /* a stack we don't know (a signal handler's, say): let it be */
    if (here < lo || here > hi || low >= lo) { return; }
    u8 *msg = "stack overflow\n";
    libc.write(2, msg, 15);
    abort();
}

/* `parts.free()` of a slice: the buffer its ptr points at, for one a
 * function allocated (str.split's list) */
pub void __ktmSliceFree(void *ptr) {
    mem.free(ptr);
}

pub void __ktmThrow(u64 v) {
    __ktmThrowAt(v, null, 0);
}

/* `catch (Error e)`: move the current error into e. */
pub void __ktmErrorTake(Error *e) {
    HCFs *fs = Fs();
    e.code = fs.error.code;
    e.message = fs.error.message ? fs.error.message : _err_none;
    e.file = fs.error.file ? fs.error.file : _err_none;
    e.line = fs.error.line;
    fs.error.message = _err_none;
}

/* Free e's message (if it has one of its own). */
pub void __ktmErrorDrop(Error *e) {
    if (ErrOwns(e.message)) { mem.free(e.message); }
    e.message = _err_none;
}

/* dst = src, with its own copy of the message. */
pub void __ktmErrorCopy(Error *dst, Error *src) {
    dst.code = src.code;
    dst.message = ErrOwns(src.message) ? cstr.dup(src.message) : _err_none;
    dst.file = src.file ? src.file : _err_none;
    dst.line = src.line;
}

/* A catch without a binding: nothing can read the message, free it. */
pub void __ktmErrorForget() {
    HCFs *fs = Fs();
    if (ErrOwns(fs.error.message)) { mem.free(fs.error.message); }
    fs.error.message = _err_none;
}

/* A defer caught a throw leaving its block: make the try that was active
 * before f the innermost again and move the error into *saved, out of the
 * way of throws caught inside the deferred statement. */
pub void __ktmDeferCaught(CatchFrame *f, Error *saved) {
    HCFs *fs = Fs();
    fs.catchTop = f.prev;
    __ktmErrorTake(saved);
}

/* After the deferred statement: *saved is the current error again (its
 * message moves back) and goes on to the next frame out. */
pub void __ktmRethrow(Error *saved) {
    HCFs *fs = Fs();
    u8 *message = saved.message;
    saved.message = _err_none;
    ErrSet(fs, saved.code, message, saved.file, saved.line);
    ErrJump(fs);
}
