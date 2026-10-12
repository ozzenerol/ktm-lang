module os;
import cstr, libc, mem;

/* End the program with exitCode (C's exit: atexit handlers run, stdio
 * is flushed) */
pub void exit(i64 exitCode=EXIT_FAIL) {
    libc.exit(exitCode);
}

#ifdef IS_LINUX
extern "c" i32 *__errno_location();
static i32 *errnoPtr() { return __errno_location(); }
#else
/* errno is *__error(); the raw name, as ktm doesn't prefix names starting with _ */
_extern ___error i32 *errnoPtr();
#endif

/* The calling thread's C errno: the reason the last failing libc call
 * (socket, connect, read, ...) gave. Only meaningful right after a call
 * that returned its error value. */
pub i32 errno() {
    return *errnoPtr();
}

/* Set the calling thread's C errno, e.g. to 0 before a call that only
 * reports errors through errno. */
pub void setErrno(i32 err) {
    *errnoPtr() = err;
}

/* Write out what printf has buffered for stdout (C's fflush(NULL)): before
 * a fork, an exec, or a long wait, when the output must show now */
pub void flush()
{
    libc.fflush(null);
}

/* printf's format to stderr (unbuffered), so it doesn't mix into what a
 * program prints on stdout */
pub void eprintf(u8 *fmt, ...args)
{
    u8 *text = cstr.formatArgs(null, fmt, args);
    libc.write(STDERR, text, libc.strlen(text));
    mem.free(text);
}
