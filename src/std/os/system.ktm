module os;
import libc;

#ifdef IS_X86_64
asm {
_EXIT::
        CALL    exit
        RET
}

pub _extern _EXIT void exit(i64 exitCode=EXIT_FAIL);
#endif

#ifdef IS_ARM_64
pub void exit(i64 exit_code=EXIT_FAIL) {
    libc.exit(exit_code);
}
#endif

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
