module rt;
import c, mem, os, str;

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
    CatchFrame *catch_top;  /* head of the active-try-frames chain */
    u64 except_ch;          /* code of the most recent throw */
    void *coro_cur;           /* running Coroutine, null on the thread's own stack */
    void *coro_root;          /* save slot for the thread's own stack (coroutines.ktm) */
    void *coro_cache;         /* released coroutine stacks kept for reuse */
    i64 coro_cached;        /* how many */
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
static i64 _fs_once[2] = {0x30B1BCBA, 0};
#else
static i64 fsOnce[2] = {0, 0};
#endif

static u64 fsKey = 0;

static void fsFree(void *p) {
    HCFs *fs = p;
    if (ErrOwns(fs.error.message)) { memFree(fs.error.message); }
    coroStacksFree(fs);
    c.free(fs.coro_root);
    c.free(fs);
}

static void fsKeyCreate() {
    c.pthread_key_create(&fsKey, &fsFree);
}

/* The calling thread's HCFs, created on its first use. */
pub HCFs *threadState() {
    c.pthread_once(fsOnce, &fsKeyCreate);
    HCFs *fs = c.pthread_getspecific(fsKey);
    if (!fs) {
        fs = c.calloc(1, sizeof(HCFs));
        if (!fs) {
            printf("out of memory for the exception state\n");
            exitProgram(1);
        }
        c.pthread_setspecific(fsKey, fs);
    }
    return fs;
}

/* Push the frame onto the catch-stack. The setjmp call itself MUST
 * stay inline in the user function (it captures the caller's frame,
 * and longjmp to a returned function's frame is UB), so the chain
 * mutation is the only thing we delegate to a helper. */
pub void __ktmPushFrame(CatchFrame *f) {
    HCFs *fs = threadState();
    f.prev = fs.catch_top;
    fs.catch_top = f;
}

pub void __ktmTryLeave(CatchFrame *f) {
    threadState().catch_top = f.prev;
}

/* Make e (a struct copy) the thread's current error; the message moves
 * in, the one there before is freed. */
static void ErrSet(HCFs *fs, u64 code, u8 *message, u8 *file, i64 line) {
    if (ErrOwns(fs.error.message)) { memFree(fs.error.message); }
    fs.error.code = code;
    fs.error.message = message ? message : _err_none;
    fs.error.file = file ? file : _err_none;
    fs.error.line = line;
    fs.except_ch = code;
}

static void WriteErr(u8 *s) {
    i64 n = 0;
    while (s[n]) { n++; }
    fdWrite(os.STDERR, s, n);
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
    c.fflush(null);  /* what the program printed comes first */
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
    exitProgram(1);
}

static void ErrJump(HCFs *fs) {
    auto t = fs.catch_top;
    if (!t) { __ktmUncaught(fs); }
    c._longjmp(t.jb, 1);
}

/* `throw(code)`: the compiler passes where the throw is. */
pub void KTM_ThrowAt(u64 code, u8 *file, i64 line) {
    HCFs *fs = threadState();
    ErrSet(fs, code, null, file, line);
    ErrJump(fs);
}

/* `throw(code, fmt, ...)`: the compiler checks the call as one to
 * KTM_ThrowFmt, then calls this with where the throw is in front. */
pub void KTM_ThrowFmtAt(u8 *file, i64 line, u64 code, u8 *fmt, ...args) {
    u8 *message = strPrintJoin(null, fmt, args.len, args);
    HCFs *fs = threadState();
    ErrSet(fs, code, message, file, line);
    ErrJump(fs);
}

pub void KTM_ThrowFmt(u64 code, u8 *fmt, ...args) {
    u8 *message = strPrintJoin(null, fmt, args.len, args);
    HCFs *fs = threadState();
    ErrSet(fs, code, message, null, 0);
    ErrJump(fs);
}

/* `throw(e)`: the same error again, with a copy of its message (the
 * catch that holds e frees its own when it is left). */
pub void KTM_ThrowError(Error *e) {
    u8 *message = ErrOwns(e.message) ? strNew(e.message) : null;
    HCFs *fs = threadState();
    ErrSet(fs, e.code, message, e.file, e.line);
    ErrJump(fs);
}

pub void __ktmThrow(u64 v) {
    KTM_ThrowAt(v, null, 0);
}

/* `catch (Error e)`: move the current error into e. */
pub void KTM_ErrorTake(Error *e) {
    HCFs *fs = threadState();
    e.code = fs.error.code;
    e.message = fs.error.message ? fs.error.message : _err_none;
    e.file = fs.error.file ? fs.error.file : _err_none;
    e.line = fs.error.line;
    fs.error.message = _err_none;
}

/* Free e's message (if it has one of its own). */
pub void KTM_ErrorDrop(Error *e) {
    if (ErrOwns(e.message)) { memFree(e.message); }
    e.message = _err_none;
}

/* dst = src, with its own copy of the message. */
pub void KTM_ErrorCopy(Error *dst, Error *src) {
    dst.code = src.code;
    dst.message = ErrOwns(src.message) ? strNew(src.message) : _err_none;
    dst.file = src.file ? src.file : _err_none;
    dst.line = src.line;
}

/* A catch without a binding: nothing can read the message, free it. */
pub void KTM_ErrorForget() {
    HCFs *fs = threadState();
    if (ErrOwns(fs.error.message)) { memFree(fs.error.message); }
    fs.error.message = _err_none;
}

/* A defer caught a throw leaving its block: make the try that was active
 * before f the innermost again and move the error into *saved, out of the
 * way of throws caught inside the deferred statement. */
pub void KTM_DeferCaught(CatchFrame *f, Error *saved) {
    HCFs *fs = threadState();
    fs.catch_top = f.prev;
    KTM_ErrorTake(saved);
}

/* After the deferred statement: *saved is the current error again (its
 * message moves back) and goes on to the next frame out. */
pub void KTM_Rethrow(Error *saved) {
    HCFs *fs = threadState();
    u8 *message = saved.message;
    saved.message = _err_none;
    ErrSet(fs, saved.code, message, saved.file, saved.line);
    ErrJump(fs);
}
