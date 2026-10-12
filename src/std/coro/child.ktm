module coro;
import date, libc, os, proc;

const i64 CORO_WNOHANG = 1;

/* waitpid(pid, WNOHANG), retried on EINTR: pid when reaped (*st set), 0
 * while it runs, -1 on an error (ECHILD) */
static i32 coroReap(i32 pid, i32 *st) {
    while (true) {
        i32 r = libc.waitpid(pid, st, CORO_WNOHANG);
        if (r >= 0 || os.errno() != libc.EINTR) { return r; }
    }
}

/* In a loop coroutine: pause until child pid (from fork, posix_spawn or
 * proc.spawn: then give p.pid and pass the result to proc.setStatus)
 * exits, reap it, and return its wait status (>= 0; proc.exitCode,
 * proc.signaled and the others read it). -1 when ms milliseconds passed
 * first (ms < 0: no limit; the child still runs, os.errno() is
 * ETIMEDOUT) or pid is not a child of ours (ECHILD). The other
 * coroutines run meanwhile; any number of them can each wait for a
 * child. Waits on proc.exitFd(pid) (a pidfd on Linux, a kqueue on
 * macOS), or by checking every few ms where the system has neither.
 * Like coro.wait, coro.stop() doesn't end it. */
pub i32 waitChild(i32 pid, i64 ms=-1) {
    LoopTask *t = loopSelf("coro.waitChild");
    i64 until = loopDeadline(ms), d, nap = 1;
    i32 st = 0, r;
    if (pid <= 0) {
        os.setErrno(libc.ECHILD);
        return -1;
    }
    r = coroReap(pid, &st);
    if (r == pid) { return st; }
    if (r < 0) { return -1; }
    i32 fd = proc.exitFd(pid);
    while (true) {
        if (fd >= 0) {
            loopPark(t, fd, libc.POLLIN, until, false, false);
        } else {
            /* no exit fd: look again after 1, 2, 4 ... up to 50 ms */
            d = date.monoMs() + nap;
            if (until >= 0 && until < d) { d = until; }
            loopPark(t, -1, 0, d, false, false);
            if (nap < 50) { nap *= 2; }
        }
        r = coroReap(pid, &st);
        if (r == pid || r < 0) { break; }
        if (until >= 0 && date.monoMs() >= until) {
            if (fd >= 0) { libc.close(fd); }
            os.setErrno(libc.ETIMEDOUT);
            return -1;
        }
    }
    if (fd >= 0) {
        i32 err = os.errno();
        libc.close(fd);
        os.setErrno(err);
    }
    return r == pid ? st : -1;
}
