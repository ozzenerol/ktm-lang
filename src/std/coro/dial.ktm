module coro;
import date, libc, mem, net, os;

/* coro.dial: connect from a loop coroutine without blocking the loop's
 * thread (net.dial waits in connect(2), and every other coroutine of the
 * loop with it). */

#ifdef IS_MACOS
const i64 DIAL_EINPROGRESS = 36;
const i64 DIAL_ETIMEDOUT = 60;
#else
const i64 DIAL_EINPROGRESS = 115;
const i64 DIAL_ETIMEDOUT = 110;
#endif

/* In a loop coroutine: a TCP connection to addr (a host name, or an
 * IPv4 or IPv6 address) on port, as a non-blocking fd ready for
 * coro.read and coro.write; -1 with os.errno() set (ECONNREFUSED,
 * ETIMEDOUT, ...) when no address the name resolves to accepts.
 * Connecting pauses only the coroutine. Every address is tried in turn,
 * within ms milliseconds in all (ms < 0: no limit). Looking up a name
 * still waits for the system's resolver: give a numeric address where
 * that matters. Close the fd with coro.close. */
pub i32 dial(u8[] addr, i32 port, i64 ms=-1) {
    u8 host[256];
    u8 strport[8];
    libc.addrinfo hints, *info, *p;
    if (port < 0 || port > 65535 || addr.len == 0 || addr.len >= sizeof(host)) {
        os.setErrno(libc.EINVAL);
        return -1;
    }
    mem.copy(host, addr.ptr, addr.len);
    host[addr.len] = 0;
    libc.snprintf(strport, sizeof(strport), "%d", port);
    mem.set(&hints, 0, sizeof(libc.addrinfo));
    hints.ai_family = libc.AF_UNSPEC;
    hints.ai_socktype = libc.SOCK_STREAM;
    if (libc.getaddrinfo(host, strport, &hints, &info) != 0) {
        os.setErrno(libc.EINVAL);
        return -1;
    }
    i64 deadline = ms >= 0 ? date.monoMs() + ms : -1;
    i32 fd = -1, err = libc.ECONNREFUSED;
    for (p = info; p; p = p.ai_next) {
        fd = libc.socket(p.ai_family, p.ai_socktype, p.ai_protocol);
        if (fd < 0) {
            err = os.errno();
            continue;
        }
        libc.fcntl(fd, libc.F_SETFD, libc.FD_CLOEXEC);
        net.setNonBlocking(fd);
        if (libc.connect(fd, p.ai_addr, p.ai_addrlen) == 0) { break; }
        err = os.errno();
        if (err == DIAL_EINPROGRESS) {
            i64 left = -1;
            if (deadline >= 0) {
                left = deadline - date.monoMs();
                if (left < 0) { left = 0; }
            }
            if (wait(fd, libc.POLLOUT, left) > 0) {
                i32 soerr = 0;
                u32 len = sizeof(i32);
                if (libc.getsockopt(fd, libc.SOL_SOCKET, libc.SO_ERROR, &soerr, &len) == 0 &&
                    soerr == 0) {
                    break;                                  /* connected */
                }
                err = soerr ? soerr : os.errno();
            } else {
                err = DIAL_ETIMEDOUT;
            }
        }
        close(fd);
        fd = -1;
        if (err == DIAL_ETIMEDOUT) { break; }   /* the time is up for every address */
    }
    libc.freeaddrinfo(info);
    if (fd < 0) { os.setErrno(err); }
    return fd;
}
