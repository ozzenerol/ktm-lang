module os;

#ifdef IS_LINUX
extern "c" i32 *__errno_location();
static i32 *errnoPtr() { return __errno_location(); }
#else
/* errno is *__error(); the raw name, as ktm doesn't prefix names starting with _ */
_extern ___error i32 *ErrnoPtr();
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
