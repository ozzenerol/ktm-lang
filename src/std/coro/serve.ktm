module coro;
import atomic, cstr, libc, mem, net, os, rt;

/* coro.serve: a TCP server on every core in one call. Each thread runs a
 * coroutine loop of its own (coro.go, coro.run) with an acceptor that
 * starts the handler as a coroutine per client. On Linux every loop has
 * its own SO_REUSEPORT listener, so the kernel spreads the connections
 * over them. macOS gives every connection to one of several such
 * listeners, so there the loops share one listener instead: each takes
 * the clients it wins the accept for. Built on coro's public calls only. */

#ifdef IS_MACOS
const i32 SERVE_SC_NPROCESSORS_ONLN = 58;
const i64 SERVE_ENOTSOCK = 38;
#else
const i32 SERVE_SC_NPROCESSORS_ONLN = 84;
const i64 SERVE_ENOTSOCK = 88;
extern "c" i32 sched_getaffinity(i32 pid, u64 size, void *mask);
#endif

extern "c" i32 sched_yield();

const i32 SERVE_BACKLOG = 4096;
const i32 SERVE_F_GETFL = 3;
const i32 SERVE_F_SETFL = 4;
const i32 SERVE_F_SETFD = 2;

struct ServeLoop;

/* One coro.serve call: its loops, and how to stop them */
struct Server {
    fn(i32 fd, void *arg) void handler;
    void *arg;
    i64 n;               /* loops (threads, the caller's included) */
    ServeLoop *loops;
    i64 stopping;        /* 1 once serveStop or a coro.stop reached it */
    i64 err;             /* errno of a listener that broke, 0 if none */
    Server *next;        /* in gServers */
};

/* One loop of a Server: its thread's listener and wake pipe */
struct ServeLoop {
    Server *s;
    i32 lfd;             /* its listener (shared on macOS) */
    i32 wakeR, wakeW;    /* serveStop writes a byte to wakeW */
    libc.pthread_t *th;  /* null for the caller's loop */
};

/* What a client coroutine needs: freed when it starts */
struct ServeClient {
    Server *s;
    i32 fd;
};

/* Every running Server, for serveStop; gServersLock is a spinlock */
static Server *gServers = null;
static i64 gServersLock = 0;

static void serveLock() {
    while (!atomic.cas(&gServersLock, 0, 1)) { sched_yield(); }
}

static void serveUnlock() {
    atomic.store(&gServersLock, 0);
}

/* The CPUs this process may run on: its affinity mask on Linux, the CPUs
 * online on macOS; at least 1 */
static i64 serveCpus() {
#ifndef IS_MACOS
    u64 mask[16];
    mem.set(mask, 0, sizeof(mask));
    if (sched_getaffinity(0, sizeof(mask), mask) == 0) {
        i64 n = 0;
        for (i64 i = 0; i < 16; i++) {
            u64 m = mask[i];
            while (m) { m &= m - 1; n++; }
        }
        if (n > 0) { return n; }
    }
#endif
    i64 c = libc.sysconf(SERVE_SC_NPROCESSORS_ONLN);
    return c > 0 ? c : 1;
}

/* A listener on port of bindaddr ("*": any IPv4 address, "::*": any IPv6
 * one, else a numeric address) with SO_REUSEADDR and, when `share`,
 * SO_REUSEPORT; -1 with errno set on failure */
static i32 serveListen(i32 port, u8[] bindaddr, bool share) {
    u8 host[64];
    u8 strport[8];
    u8 *node = host;
    libc.addrinfo hints, *info, *p;
    i32 fd = -1, err = libc.EINVAL, yes = 1;
    if (port < 0 || port > 65535 || bindaddr.len == 0 || bindaddr.len >= sizeof(host)) {
        os.setErrno(libc.EINVAL);
        return -1;
    }
    mem.copy(host, bindaddr.ptr, bindaddr.len);
    host[bindaddr.len] = 0;
    mem.set(&hints, 0, sizeof(libc.addrinfo));
    hints.ai_family = libc.AF_UNSPEC;
    if (!cstr.cmp(host, "::*")) {
        hints.ai_family = libc.AF_INET6;
        node = null;
    } else if (!cstr.cmp(host, "*")) {
        hints.ai_family = libc.AF_INET;
        node = null;
    }
    hints.ai_socktype = libc.SOCK_STREAM;
    hints.ai_flags = libc.AI_PASSIVE | libc.AI_NUMERICHOST;
    libc.snprintf(strport, sizeof(strport), "%d", port);
    if (libc.getaddrinfo(node, strport, &hints, &info) != 0) {
        os.setErrno(libc.EINVAL);
        return -1;
    }
    for (p = info; p; p = p.ai_next) {
        fd = libc.socket(p.ai_family, p.ai_socktype, p.ai_protocol);
        if (fd < 0) { err = os.errno(); continue; }
        libc.setsockopt(fd, libc.SOL_SOCKET, libc.SO_REUSEADDR, &yes, sizeof(i32));
        if (share) {
            libc.setsockopt(fd, libc.SOL_SOCKET, libc.SO_REUSEPORT, &yes, sizeof(i32));
        }
        if (libc.bind(fd, p.ai_addr, p.ai_addrlen) == 0 &&
            libc.listen(fd, SERVE_BACKLOG) == 0) {
            break;
        }
        err = os.errno();
        libc.close(fd);
        fd = -1;
    }
    libc.freeaddrinfo(info);
    if (fd < 0) { os.setErrno(err); }
    return fd;
}

/* A non-blocking, close-on-exec pipe for a loop's wake-up */
static bool serveWakePipe(ServeLoop *l) {
    i32 fds[2];
    if (libc.pipe(fds) != 0) { return false; }
    for (i64 i = 0; i < 2; i++) {
        libc.fcntl(fds[i], SERVE_F_SETFD, 1);
        libc.fcntl(fds[i], SERVE_F_SETFL, libc.fcntl(fds[i], SERVE_F_GETFL, 0) | libc.O_NONBLOCK);
    }
    l.wakeR = fds[0];
    l.wakeW = fds[1];
    return true;
}

/* Stop every loop of s (once): each one's watcher wakes and stops it */
static void serveStopServer(Server *s) {
    if (!atomic.cas(&s.stopping, 0, 1)) { return; }
    u8 b = 1;
    for (i64 i = 0; i < s.n; i++) { libc.write(s.loops[i].wakeW, &b, 1); }
}

/* Stop every coro.serve running in the process, from any thread, a loop
 * coroutine or a signal coroutine (not from inside a signal handler):
 * each loop stops taking clients, the handlers in flight finish, and each
 * serve call returns 0 then. */
pub void serveStop() {
    serveLock();
    for (Server *s = gServers; s; s = s.next) { serveStopServer(s); }
    serveUnlock();
}

static void *serveClient(void *p) {
    ServeClient *c = p;
    Server *s = c.s;
    i32 fd = c.fd;
    mem.free(c);
    try {
        s.handler(fd, s.arg);
    } catch (Error e) {
        os.eprintf("coro.serve: the handler threw 0x%x at %s:%d: %s\n",
                   e.code, e.file, e.line, e.message);
    }
    close(fd);
    return null;
}

static void *serveAcceptor(void *p) {
    ServeLoop *l = p;
    while (true) {
        i32 fd = accept(l.lfd);
        if (fd < 0) {
            if (stopping()) { break; }
            i32 err = os.errno();
            if (err == libc.EBADF || err == libc.EINVAL || err == SERVE_ENOTSOCK) {
                l.s.err = err;   /* the listener broke: stop the server */
                serveStopServer(l.s);
                break;
            }
            continue;
        }
        net.setNoDelay(fd);
        ServeClient *c = mem.alloc(sizeof(ServeClient));
        if (c) {
            c.s = l.s;
            c.fd = fd;
        }
        if (!c || !go(&serveClient, c)) {
            mem.free(c);
            close(fd);
        }
    }
    return null;
}

/* Waits for serveStop (its wake pipe) or a coro.stop() of this loop;
 * either one stops the whole server */
static void *serveWatcher(void *p) {
    ServeLoop *l = p;
    waitIdle(l.wakeR, libc.POLLIN);
    serveStopServer(l.s);
    stop();
    return null;
}

/* Run one loop: until it is stopped and its handlers have finished */
static void serveRun(ServeLoop *l) {
    go(&serveWatcher, l);
    go(&serveAcceptor, l);
    while (true) {
        try {
            run();
            return;
        } catch (Error e) {
            os.eprintf("coro.serve: a coroutine threw 0x%x at %s:%d: %s\n",
                       e.code, e.file, e.line, e.message);
        }
    }
}

static void *serveThread(void *p) {
    rt.__ktmCrashThread();
    serveRun(p);
    return null;
}

/* Close every fd of s's loops made so far (the first n) and free them */
static void serveFree(Server *s, i64 n, bool shared) {
    for (i64 i = 0; i < n; i++) {
        ServeLoop *l = &s.loops[i];
        if (l.lfd >= 0 && (!shared || i == 0)) { libc.close(l.lfd); }
        if (l.wakeR >= 0) { libc.close(l.wakeR); }
        if (l.wakeW >= 0) { libc.close(l.wakeW); }
    }
    mem.free(s.loops);
}

/* A TCP server on port of bindaddr ("*": every IPv4 address, "::*":
 * every IPv6 one, or a numeric address) that runs handler(fd, arg) as a
 * coroutine for each client, on `threads` coroutine loops (0: one per CPU
 * the process may use), the calling thread's included. The client's fd
 * is non-blocking with TCP_NODELAY set: wait on it with coro.wait, and
 * don't close it, serve closes it with coro.close when the handler
 * returns. A handler that throws is reported on stderr; the server goes
 * on. Handlers run on several threads at once: a global they share
 * needs the atomic module or a lock.
 *
 * Returns 0 once serveStop() or a coro.stop() in one of its loops (from
 * a coroutine started before serve, e.g. on signal.stopInit()'s fd)
 * stopped it and every handler has finished; -1 with os.errno() set if
 * the listeners can't be made (a bad port or address, the port in use)
 * or a listener broke later. Call it from a thread, not a coroutine. */
pub i32 serve(i32 port, fn(i32 fd, void *arg) void handler, void *arg = null,
              i64 threads = 0, u8[] bindaddr = "*") {
    if (current()) { throw(ERR_LOOP, "coro.serve: called inside a coroutine"); }
    if (threads <= 0) { threads = serveCpus(); }
#ifdef IS_MACOS
    bool shared = threads > 1;   /* one listener for every loop */
#else
    bool shared = false;
#endif
    Server s;
    mem.set(&s, 0, sizeof(Server));
    s.handler = handler;
    s.arg = arg;
    s.n = threads;
    s.loops = mem.allocZero(threads * sizeof(ServeLoop));
    if (!s.loops) { os.setErrno(libc.ENOMEM); return -1; }
    for (i64 i = 0; i < threads; i++) {
        ServeLoop *l = &s.loops[i];
        l.s = &s;
        l.lfd = l.wakeR = l.wakeW = -1;
        if (shared && i > 0) {
            l.lfd = s.loops[0].lfd;
        } else {
            l.lfd = serveListen(port, bindaddr, !shared && threads > 1);
        }
        if (l.lfd < 0 || !serveWakePipe(l)) {
            i32 err = os.errno();
            serveFree(&s, i + 1, shared);
            os.setErrno(err);
            return -1;
        }
    }
    serveLock();
    s.next = gServers;
    gServers = &s;
    serveUnlock();

    i64 started = 1;
    for (i64 i = 1; i < threads; i++) {
        if (libc.pthread_create(&s.loops[i].th, null, &serveThread, &s.loops[i]) != 0) {
            break;   /* fewer threads: the ones running still serve */
        }
        started++;
    }
    for (i64 i = started; i < threads; i++) {
        /* a listener no loop accepts on would hold the clients it gets */
        if (!shared) { libc.close(s.loops[i].lfd); }
        s.loops[i].lfd = -1;
    }
    serveRun(&s.loops[0]);
    for (i64 i = 1; i < started; i++) { libc.pthread_join(s.loops[i].th); }

    serveLock();
    if (gServers == &s) {
        gServers = s.next;
    } else {
        for (Server *q = gServers; q; q = q.next) {
            if (q.next == &s) { q.next = s.next; break; }
        }
    }
    serveUnlock();
    i64 err = s.err;
    serveFree(&s, threads, shared);
    if (err) {
        os.setErrno(err);
        return -1;
    }
    return 0;
}
