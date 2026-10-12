module proc;
import libc, os;

/* A file descriptor that polls readable (POLLIN) once the child pid has
 * exited, for a poll(2) loop or coro.wait. Linux: a pidfd
 * (pidfd_open(2), kernel 5.3 and later); macOS: a kqueue watching the
 * child with EVFILT_PROC NOTE_EXIT. It doesn't reap the child: waitpid
 * it (or proc.done) once the fd is readable, then close the fd. -1, with
 * errno set, when the system can't make one (ENOSYS on an older Linux
 * kernel, ESRCH when there is no such process or, on macOS, it has
 * already exited): then poll with waitpid(pid, WNOHANG) instead. The fd
 * is close-on-exec. */
#ifdef IS_LINUX
extern "c" i64 syscall(i64 number, ...);
const i64 PROC_SYS_PIDFD_OPEN = 434;   /* the same on x86_64 and arm64 */

pub i32 exitFd(i32 pid)
{
    if (pid <= 0) {
        os.setErrno(libc.ESRCH);
        return -1;
    }
    return syscall(PROC_SYS_PIDFD_OPEN, pid, 0);
}
#else
struct ProcKevent {
    u64 ident;
    i16 filter;
    u16 flags;
    u32 fflags;
    i64 data;
    void *udata;
};
extern "c" i32 kqueue();
extern "c" i32 kevent(i32 kq, ProcKevent *changes, i32 nchanges, ProcKevent *events,
                      i32 nevents, void *timeout);
const i64 PROC_EVFILT_PROC = -5;
const i64 PROC_EV_ADD = 0x1;
const i64 PROC_EV_ONESHOT = 0x10;
const i64 PROC_NOTE_EXIT = 0x80000000;

pub i32 exitFd(i32 pid)
{
    if (pid <= 0) {
        os.setErrno(libc.ESRCH);
        return -1;
    }
    i32 kq = kqueue();
    if (kq < 0) { return -1; }
    libc.fcntl(kq, libc.F_SETFD, libc.FD_CLOEXEC);
    ProcKevent ev;
    ev.ident = pid;
    ev.filter = PROC_EVFILT_PROC;
    ev.flags = PROC_EV_ADD | PROC_EV_ONESHOT;
    ev.fflags = PROC_NOTE_EXIT;
    ev.data = 0;
    ev.udata = null;
    if (kevent(kq, &ev, 1, null, 0, null) < 0) {
        i32 err = os.errno();
        libc.close(kq);
        os.setErrno(err);
        return -1;
    }
    return kq;
}
#endif
