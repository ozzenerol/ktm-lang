module rt;
import libc, mem, os, str;

/* strings.ktm */

/* Compiler reserves 256 bytes per CatchFrame. We split that into a
 * 248-byte jmp_buf storage region (setjmp owns the format) plus the
 * 8-byte chain pointer the runtime walks. */
const i64 __JMP_BUF_BYTES__ = 248;

pub struct CatchFrame;

pub struct CatchFrame {
    i8 jb[__JMP_BUF_BYTES__];
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
    coroStacksFree(fs);
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
    fs.catchTop = f;
}

pub void __ktmTryLeave(CatchFrame *f) {
    Fs().catchTop = f.prev;
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
    u8 *message = str.formatArgs(null, fmt, args.len, args);
    HCFs *fs = Fs();
    ErrSet(fs, code, message, file, line);
    ErrJump(fs);
}

pub void __ktmThrowFmt(u64 code, u8 *fmt, ...args) {
    u8 *message = str.formatArgs(null, fmt, args.len, args);
    HCFs *fs = Fs();
    ErrSet(fs, code, message, null, 0);
    ErrJump(fs);
}

/* `throw(e)`: the same error again, with a copy of its message (the
 * catch that holds e frees its own when it is left). */
pub void __ktmThrowError(Error *e) {
    u8 *message = ErrOwns(e.message) ? str.dup(e.message) : null;
    HCFs *fs = Fs();
    ErrSet(fs, e.code, message, e.file, e.line);
    ErrJump(fs);
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
    dst.message = ErrOwns(src.message) ? str.dup(src.message) : _err_none;
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
