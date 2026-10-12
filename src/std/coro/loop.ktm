module coro;
import date, libc, mem, net, os, rt;

/* A loop of coroutines: coro.go starts one, coro.run runs them until none
 * is left, and inside them coro.wait, coro.sleep and coro.accept pause the
 * coroutine instead of blocking the thread. Each thread has its own loop
 * (kept in its rt.HCFs, made on first use).
 *
 * The waits on fds go to the kernel's event queue, epoll(7) on Linux and
 * kqueue(2) on macOS, so a turn costs what is ready, not what is open. An
 * fd stays registered (level-triggered) for what its waiters want, and the
 * queue is only told when that changes: a connection that waits to read,
 * answers, and waits to read again costs no call to the queue after its
 * first wait. One that fires while nobody waits for that is narrowed then.
 * Several coroutines may wait on one fd (a reader and a writer): each fd
 * keeps its waiters and is registered for all their events. coro.close
 * and coro.accept forget a closed fd's registration (the kernel drops it
 * on close(2)), so close the fds you wait on with coro.close. */

/* The code of what the loop throws: run() inside a coroutine, wait() in a
 * coroutine coro.go didn't start, every coroutine waiting forever, poll
 * failing. */
pub const i64 ERR_LOOP = 0x6B746D13;

/* Out of fds, accept tries again after this long at the latest, and
 * checks for free fds first for this long after. */
const i64 LOOP_PAUSE_MS = 2000;
const i64 LOOP_TIGHT_MS = 10000;

#ifdef IS_MACOS
const i64 LOOP_ECONNABORTED = 53;
const i64 LOOP_ENOBUFS = 55;
const i64 LOOP_ENOTSOCK = 38;
#else
const i64 LOOP_ECONNABORTED = 103;
const i64 LOOP_ENOBUFS = 105;
const i64 LOOP_ENOTSOCK = 88;
#endif

#ifdef IS_LINUX
extern "c" i32 epoll_create1(i32 flags);
extern "c" i32 epoll_ctl(i32 epfd, i32 op, i32 fd, void *event);
extern "c" i32 epoll_wait(i32 epfd, void *events, i32 maxevents, i32 timeout);
const i64 LOOP_EPOLL_CLOEXEC = 0x80000;
const i64 LOOP_EPOLL_CTL_ADD = 1;
const i64 LOOP_EPOLL_CTL_DEL = 2;
const i64 LOOP_EPOLL_CTL_MOD = 3;
/* struct epoll_event: u32 events, then u64 data, packed on x86_64 */
#ifdef IS_X86_64
const i64 LOOP_EV_SIZE = 12;
const i64 LOOP_EV_DATA = 4;
#else
const i64 LOOP_EV_SIZE = 16;
const i64 LOOP_EV_DATA = 8;
#endif
#else
struct LoopKevent {
    u64 ident;
    i16 filter;
    u16 flags;
    u32 fflags;
    i64 data;
    void *udata;
};
extern "c" i32 kqueue();
extern "c" i32 kevent(i32 kq, void *changes, i32 nchanges, void *events, i32 nevents,
                      libc.timespec *timeout);
const i64 LOOP_EVFILT_READ = -1;
const i64 LOOP_EVFILT_WRITE = -2;
const i64 LOOP_EV_ADD = 0x1;
const i64 LOOP_EV_DELETE = 0x2;
const i64 LOOP_EV_ERROR = 0x4000;
const i64 LOOP_EV_EOF = 0x8000;
const i64 LOOP_EV_SIZE = 32;
#endif
/* events taken from the kernel in one turn at most (more wait a turn) */
const i64 LOOP_MAXEV = 256;
/* coro.read reads at most this many times in a row without a turn of the
 * loop, so one busy stream doesn't starve the others */
const i64 LOOP_READ_STREAK = 32;

/* The coroutines waiting on one fd, and what it is registered for */
struct LoopFd {
    LoopTask **w;
    i64 n, cap;
    i64 armed;           /* POLLIN/POLLOUT it is registered for, 0: none */
    bool full;           /* coro.read's last read filled its buffer */
    i64 streak;          /* reads in a row that didn't wait */
};

struct LoopTask {
    Coroutine *co;
    i64 at;              /* its index in Loop.tasks */
    i32 fd;              /* the fd its wait polls, -1: none */
    i16 events;
    i64 deadline;        /* date.monoMs() limit of its wait, -1: none */
    bool onFd;           /* in the waiters of fd */
    i64 result;          /* what the wait returns */
    bool waiting;        /* paused in a wait */
    bool ready;          /* in Loop.ready, to be resumed with `result` */
    bool idle;           /* stop() ends the wait with -1 */
    bool paused;         /* accept out of fds: close() ends the wait */
};

struct Loop {
    LoopTask **tasks;    /* every coroutine of the loop */
    i64 n, cap;          /* cap: also of ready and batch */
    LoopTask **ready;    /* to resume next, in order */
    i64 nready;
    LoopTask **batch;    /* the ones being resumed this turn */
    i64 batchAt, batchN;
    i32 q;               /* the epoll or kqueue fd, -1: poll(2) instead */
    libc.pollfd *pfds;   /* poll(2) only: the fds of one turn */
    i64 pfdsCap;
    u8 *ev;              /* LOOP_MAXEV events from the kernel */
    LoopFd **fdw;        /* by fd: its waiters (null: none ever) */
    i64 fdwCap;
    i64 nonFd;           /* tasks waiting on an fd */
    i64 ndeadline;       /* waits with a deadline (at least; a turn recounts) */
    bool running, stopping;
    bool fdsOut;         /* accept ran out of fds (or memory) */
    i64 fdsOutUntil;     /* and rests until then (or a close()) */
    i64 fdsTightUntil;   /* until then, accept checks for free fds first */
};

static void loopOom() {
    u8 *msg = "out of memory for the coroutine loop\n";
    libc.write(os.STDERR, msg, 37);
    os.exit(1);
}

/* The deadline of a wait of ms milliseconds, -1 (none) for ms < 0. */
static i64 loopDeadline(i64 ms) {
    if (ms < 0) { return -1; }
    return date.monoMs() + ms;
}

/* Unmap a coroutine the loop never finished. Not coro.release: this runs
 * when the thread ends, where rt.Fs() would make a new state. */
static void loopDrop(Coroutine *c) {
    rt.__ktmErrorDrop(&c.error);
    libc.munmap(c.stackBase, c.stackLen);
    libc.free(c);
}

static void loopFree(void *p) {
    Loop *lp = p;
    for (i64 i = 0; i < lp.n; i++) {
        loopDrop(lp.tasks[i].co);
        libc.free(lp.tasks[i]);
    }
    libc.free(lp.tasks);
    libc.free(lp.ready);
    libc.free(lp.batch);
    for (i64 i = 0; i < lp.fdwCap; i++) {
        if (lp.fdw[i]) {
            libc.free(lp.fdw[i].w);
            libc.free(lp.fdw[i]);
        }
    }
    libc.free(lp.fdw);
    libc.free(lp.ev);
    libc.free(lp.pfds);
    if (lp.q >= 0) { libc.close(lp.q); }
    libc.free(lp);
}

static i32 loopQueue(Loop *lp);

/* The calling thread's loop; made when `make`, else null if none. */
static Loop *loopGet(bool make) {
    rt.HCFs *fs = rt.Fs();
    Loop *lp = fs.coroLoop;
    if (!lp && make) {
        lp = libc.calloc(1, sizeof(Loop));
        if (!lp) { loopOom(); }
        lp.q = -1;
        fs.coroLoop = lp;
        fs.coroLoopFree = &loopFree;
        /* now, not at the first wait: a program may use up its fds first,
         * and then gets poll(2), which needs none */
        loopQueue(lp);
    }
    return lp;
}

static void *loopGrow(void *p, i64 cap, i64 size) {
    void *q = libc.realloc(p, cap * size);
    if (!q) { loopOom(); }
    return q;
}

static void loopAdd(Loop *lp, LoopTask *t) {
    if (lp.n == lp.cap) {
        lp.cap = lp.cap ? lp.cap * 2 : 16;
        lp.tasks = loopGrow(lp.tasks, lp.cap, sizeof(LoopTask *));
        lp.ready = loopGrow(lp.ready, lp.cap, sizeof(LoopTask *));
        lp.batch = loopGrow(lp.batch, lp.cap, sizeof(LoopTask *));
    }
    t.at = lp.n;
    lp.tasks[lp.n++] = t;
}

/* t's coroutine ended: release it and take it out of the loop. */
static void loopRemove(Loop *lp, LoopTask *t) {
    LoopTask *last = lp.tasks[--lp.n];
    lp.tasks[t.at] = last;
    last.at = t.at;
    release(t.co);
    libc.free(t);
}

/* t no longer waits on its fd (it fired, timed out, or was stopped). The
 * fd stays armed for the others; a one-shot event nobody waits for is
 * dropped when it fires. */
static void loopUnwait(Loop *lp, LoopTask *t) {
    if (!t.onFd) { return; }
    t.onFd = false;
    lp.nonFd--;
    LoopFd *f = lp.fdw[t.fd];
    for (i64 i = 0; i < f.n; i++) {
        if (f.w[i] == t) {
            f.w[i] = f.w[--f.n];
            return;
        }
    }
}

/* Queue t to be resumed with its wait returning result. */
static void loopReady(Loop *lp, LoopTask *t, i64 result) {
    loopUnwait(lp, t);
    t.ready = true;
    t.result = result;
    lp.ready[lp.nready++] = t;
}

/* Start body(arg) as a coroutine of this thread's loop: it first runs in
 * the next turn of coro.run (or when coro.run is called), and the loop
 * releases it when its function returns. If it throws, coro.run throws
 * that error on (see coro.run). stackSize as for coro.spawn. Returns the
 * coroutine (only valid until it ends), null if its stack can't be
 * mapped. Can be called inside a loop coroutine, or before coro.run. */
pub Coroutine *go(fn(void *arg) void *body, void *arg=null, i64 stackSize=0) {
    Coroutine *c = spawn(body, arg, stackSize);
    if (!c) { return null; }
    LoopTask *t = libc.calloc(1, sizeof(LoopTask));
    if (!t) {
        release(c);
        return null;
    }
    Loop *lp = loopGet(true);
    t.co = c;
    t.fd = -1;
    t.deadline = -1;
    c.loopTask = t;
    loopAdd(lp, t);
    loopReady(lp, t, 0);
    return c;
}

/* The loop's kernel queue, made with the loop; -1 when the system
 * wouldn't make one (out of fds): the loop then polls with poll(2). */
static i32 loopQueue(Loop *lp) {
    if (lp.q >= 0 || lp.ev) { return lp.q; }
#ifdef IS_LINUX
    lp.q = epoll_create1(LOOP_EPOLL_CLOEXEC);
#else
    lp.q = kqueue();
    if (lp.q >= 0) { libc.fcntl(lp.q, libc.F_SETFD, libc.FD_CLOEXEC); }
#endif
    lp.ev = libc.malloc(LOOP_MAXEV * LOOP_EV_SIZE);
    if (!lp.ev) { loopOom(); }
    return lp.q;
}

/* Every waiter of fd that `revents` answers, ready with it. Not the ones
 * waiting for something else (a writer when only reading is possible).
 * How many it readied. */
static i64 loopFire(Loop *lp, i32 fd, i64 revents) {
    if (fd < 0 || fd >= lp.fdwCap || !lp.fdw[fd]) { return 0; }
    LoopFd *f = lp.fdw[fd];
    i64 i = 0, woken = 0;
    while (i < f.n) {
        LoopTask *t = f.w[i];
        i64 r = revents & (t.events | libc.POLLERR | libc.POLLHUP | libc.POLLNVAL);
        if (r && !t.ready) {
            loopReady(lp, t, r);     /* takes it out of f.w: same i again */
            woken++;
        } else {
            i++;
        }
    }
    return woken;
}

/* What fd's waiters want, of POLLIN (POLLPRI counts as it) and POLLOUT */
static i64 loopWant(LoopFd *f) {
    i64 want = 0;
    for (i64 i = 0; i < f.n; i++) { want |= f.w[i].events; }
    if (want & libc.POLLPRI) { want |= libc.POLLIN; }
    return want & (libc.POLLIN | libc.POLLOUT);
}

/* Register fd for exactly `want` (0: not at all), telling the queue only
 * what changed. false when the queue refused it (errno set). */
static bool loopRegister(Loop *lp, i32 fd, LoopFd *f, i64 want) {
    if (want == f.armed) { return true; }
#ifdef IS_LINUX
    u8 e[16];
    u32 events = (u32)want;
    u64 data = (u64)fd;
    mem.set(e, 0, 16);
    mem.copy(e, &events, 4);
    mem.copy(e + LOOP_EV_DATA, &data, 8);
    i32 r;
    if (!want) {
        r = epoll_ctl(lp.q, LOOP_EPOLL_CTL_DEL, fd, e);
        f.armed = 0;
        return r == 0 || os.errno() == libc.ENOENT || os.errno() == libc.EBADF;
    }
    r = epoll_ctl(lp.q, f.armed ? LOOP_EPOLL_CTL_MOD : LOOP_EPOLL_CTL_ADD, fd, e);
    if (r != 0 && os.errno() == libc.ENOENT) {
        r = epoll_ctl(lp.q, LOOP_EPOLL_CTL_ADD, fd, e);
    } else if (r != 0 && os.errno() == libc.EEXIST) {
        r = epoll_ctl(lp.q, LOOP_EPOLL_CTL_MOD, fd, e);
    }
    if (r != 0) {
        f.armed = 0;
        return false;
    }
#else
    LoopKevent ch[2];
    i32 n = 0;
    i64 bits[2] = {libc.POLLIN, libc.POLLOUT};
    i16 filters[2] = {LOOP_EVFILT_READ, LOOP_EVFILT_WRITE};
    for (i64 k = 0; k < 2; k++) {
        i64 bit = bits[k];
        if ((want & bit) == (f.armed & bit)) { continue; }
        mem.set(&ch[n], 0, sizeof(LoopKevent));
        ch[n].ident = (u64)fd;
        ch[n].filter = filters[k];
        ch[n].flags = (want & bit) ? LOOP_EV_ADD : LOOP_EV_DELETE;
        n++;
    }
    if (n && kevent(lp.q, ch, n, null, 0, null) != 0) {
        i32 err = os.errno();
        if (want) {
            f.armed = 0;
            os.setErrno(err);
            return false;
        }
    }
#endif
    f.armed = want;
    return true;
}

/* Register fd for what every one of its waiters wants. An fd the queue
 * refuses (a regular file: always ready to poll(2)) answers at once; one
 * that is closed gets POLLNVAL, as poll would say. */
static void loopArm(Loop *lp, i32 fd) {
    if (lp.q < 0) { return; }       /* poll(2): each turn polls the waiters */
    LoopFd *f = lp.fdw[fd];
    i64 want = loopWant(f);
    if (!want || loopRegister(lp, fd, f, want | f.armed)) { return; }
    i32 err = os.errno();
    i64 all = 0;
    for (i64 i = 0; i < f.n; i++) { all |= f.w[i].events; }
    loopFire(lp, fd, err == libc.EBADF ? libc.POLLNVAL : all);
}

/* A closed fd: the kernel dropped its registration, so a new fd with the
 * same number starts unregistered */
static void loopForget(Loop *lp, i32 fd) {
    if (fd >= 0 && fd < lp.fdwCap && lp.fdw[fd]) {
        lp.fdw[fd].armed = 0;
        lp.fdw[fd].full = false;
    }
}

/* t waits on fd for events: one more waiter of fd, and fd armed. */
static LoopFd *loopFdOf(Loop *lp, i32 fd);

static void loopWaitFd(Loop *lp, LoopTask *t, i32 fd) {
    LoopFd *f = loopFdOf(lp, fd);
    if (f.n == f.cap) {
        f.cap = f.cap ? f.cap * 2 : 2;
        f.w = loopGrow(f.w, f.cap, sizeof(LoopTask *));
    }
    f.w[f.n++] = t;
    t.onFd = true;
    lp.nonFd++;
    loopArm(lp, fd);
}

/* The running loop coroutine's task; throws if there is none. */
static LoopTask *loopSelf(u8 *what) {
    Coroutine *c = rt.Fs().coroCur;
    if (!c || !c.loopTask) {
        throw(ERR_LOOP, "%s: not inside a coroutine that coro.go started", what);
    }
    return c.loopTask;
}

/* Pause t until fd (if >= 0) is ready for events, the deadline (if >= 0)
 * passes, or stop() (idle) or close() (paused) ends the wait. */
static i64 loopPark(LoopTask *t, i32 fd, i16 events, i64 deadline, bool idle, bool paused) {
    Loop *lp = loopGet(true);
    t.fd = fd;
    t.events = events;
    t.deadline = deadline;
    t.idle = idle;
    t.paused = paused;
    t.result = 0;
    t.waiting = true;
    if (deadline >= 0) { lp.ndeadline++; }
    if (fd >= 0) {
        loopWaitFd(lp, t, fd);      /* may answer at once (ready below) */
    }
    yield();
    return t.result;
}

/* In a loop coroutine: pause until fd is ready for events (POLLIN,
 * POLLOUT, ...) and return its revents (> 0; POLLHUP, POLLERR and
 * POLLNVAL count), or 0 once ms milliseconds passed (ms < 0: no limit).
 * stop() doesn't end it, so work in flight finishes. */
pub i64 wait(i32 fd, i16 events, i64 ms=-1) {
    LoopTask *t = loopSelf("coro.wait");
    return loopPark(t, fd, events, loopDeadline(ms), false, false);
}

/* coro.wait for "waiting for more work" (the next request, the next
 * client): the same, but -1 at once when coro.stop() was called, and -1
 * when it is called during the wait. */
pub i64 waitIdle(i32 fd, i16 events, i64 ms=-1) {
    LoopTask *t = loopSelf("coro.waitIdle");
    if (loopGet(true).stopping) { return -1; }
    return loopPark(t, fd, events, loopDeadline(ms), true, false);
}

/* fd's waiters record (made on first use) */
static LoopFd *loopFdOf(Loop *lp, i32 fd) {
    if (fd >= lp.fdwCap) {
        i64 cap = lp.fdwCap ? lp.fdwCap : 64;
        while (cap <= fd) { cap *= 2; }
        lp.fdw = loopGrow(lp.fdw, cap, sizeof(LoopFd *));
        for (i64 i = lp.fdwCap; i < cap; i++) { lp.fdw[i] = null; }
        lp.fdwCap = cap;
    }
    if (!lp.fdw[fd]) {
        lp.fdw[fd] = libc.calloc(1, sizeof(LoopFd));
        if (!lp.fdw[fd]) { loopOom(); }
    }
    return lp.fdw[fd];
}

/* In a loop coroutine: read what is there from the non-blocking fd into
 * buf, pausing the coroutine until there is something (or ms passed;
 * ms < 0: no limit). Returns the bytes read (at most buf.len), 0 at end
 * of file or when the peer closed, -1 on an error or the timeout
 * (os.errno() ETIMEDOUT). It reads at once when the last read filled
 * its buffer (more is likely waiting, as in a stream), and waits first
 * otherwise (the next request isn't there yet): either way a coroutine
 * per connection costs no more system calls than it must. A few reads
 * in a row at most skip the wait, so the loop's other coroutines get
 * their turn. */
pub i64 read(i32 fd, u8[] buf, i64 ms=-1) {
    LoopTask *t = loopSelf("coro.read");
    Loop *lp = loopGet(true);
    LoopFd *f = fd >= 0 ? loopFdOf(lp, fd) : null;
    if (!f || buf.len == 0) {
        if (!f) { os.setErrno(libc.EBADF); return -1; }
        return 0;
    }
    i64 until = loopDeadline(ms);
    bool tryFirst = f.full && f.streak < LOOP_READ_STREAK;
    f.streak = tryFirst ? f.streak + 1 : 0;
    while (true) {
        if (!tryFirst) {
            if (loopPark(t, fd, libc.POLLIN, until, false, false) == 0) {
                os.setErrno(libc.ETIMEDOUT);
                return -1;
            }
        }
        tryFirst = false;
        i64 n = libc.read(fd, buf.ptr, buf.len);
        if (n >= 0) {
            f = lp.fdw[fd];
            f.full = n == buf.len;
            return n;
        }
        i32 err = os.errno();
        if (err != libc.EAGAIN && err != libc.EWOULDBLOCK && err != libc.EINTR) { return -1; }
        lp.fdw[fd].full = false;
    }
    return -1;
}

/* In a loop coroutine: write all of data to the non-blocking fd, pausing
 * the coroutine while the fd is full (until ms passed; ms < 0: no
 * limit): first for a turn or two of the loop (the peer is usually
 * reading already), then until the fd is writable. Returns data.len, or
 * -1 on an error (EPIPE for a peer that closed: never a SIGPIPE) or the
 * timeout (ETIMEDOUT); the bytes sent before that are gone. */
pub i64 write(i32 fd, u8[] data, i64 ms=-1) {
    LoopTask *t = loopSelf("coro.write");
    i64 until = loopDeadline(ms), off = 0, yields = 0;
    while (off < data.len) {
        i64 w = net.send(fd, data[off:data.len]);
        if (w < 0 && os.errno() == LOOP_ENOTSOCK) {
            w = libc.write(fd, data.ptr + off, data.len - off);   /* a pipe */
        }
        if (w > 0) {
            off += w;
            continue;
        }
        i32 err = os.errno();
        if (w < 0 && err != libc.EAGAIN && err != libc.EWOULDBLOCK && err != libc.EINTR) {
            return -1;
        }
        if (w < 0 && err == libc.EINTR) { continue; }
        /* full: let the loop's others run one turn and try again before
         * registering for POLLOUT (the peer is usually reading) */
        if (yields < 2) {
            yields++;
            loopPark(t, -1, 0, date.monoMs(), false, false);
            continue;
        }
        yields = 0;
        if (loopPark(t, fd, libc.POLLOUT, until, false, false) == 0) {
            os.setErrno(libc.ETIMEDOUT);
            return -1;
        }
    }
    return data.len;
}

/* In a loop coroutine: pause for ms milliseconds while the others run
 * (0: just let them have a turn). */
pub void sleep(i64 ms) {
    LoopTask *t = loopSelf("coro.sleep");
    if (ms < 0) { ms = 0; }
    loopPark(t, -1, 0, loopDeadline(ms), false, false);
}

/* Two fds free? accept() needs one, and the connection it makes needs
 * another to do its work (open a file, ...). Probed with dup(lfd); false
 * only when that runs out of fds. */
static bool loopFdsFree(i32 lfd) {
    i32 a = libc.dup(lfd), b, err;
    if (a < 0) {
        err = os.errno();
        return err != libc.EMFILE && err != libc.ENFILE;
    }
    b = libc.dup(lfd);
    err = os.errno();
    libc.close(a);
    if (b < 0) { return err != libc.EMFILE && err != libc.ENFILE; }
    libc.close(b);
    return true;
}

/* Out of fds (or memory): every coro.accept of the loop rests, and checks
 * for free fds before each accept() for a while after. */
static void loopFdsOut(Loop *lp) {
    i64 now = date.monoMs();
    lp.fdsOut = true;
    lp.fdsOutUntil = now + LOOP_PAUSE_MS;
    lp.fdsTightUntil = now + LOOP_TIGHT_MS;
}

/* In a loop coroutine: the next client of listening socket lfd, as a
 * non-blocking fd (lfd is made non-blocking too); -1 when ms passed
 * (ms < 0: no limit), on coro.stop(), or when lfd can't accept (EBADF,
 * EINVAL: os.errno() says which). Out of fds or memory (EMFILE, ENFILE,
 * ENOBUFS, ENOMEM) it neither spins nor retries at once: every
 * coro.accept of the loop rests until coro.close() frees an fd, or 2 s
 * passed. For 10 s after that it checks with dup(lfd) before each
 * accept() that two fds are free (one for the client, one for its work)
 * and rests again if not: on macOS a failed accept() has already taken
 * the client off the queue and resets it. */
pub i32 accept(i32 lfd, i64 ms=-1) {
    LoopTask *t = loopSelf("coro.accept");
    Loop *lp = loopGet(true);
    i64 until = loopDeadline(ms), d, r;
    i32 fd, err;
    net.setNonBlocking(lfd);
    while (!lp.stopping) {
        if (lp.fdsOut && date.monoMs() < lp.fdsOutUntil) {
            d = lp.fdsOutUntil;
            if (until >= 0 && until < d) { d = until; }
            r = loopPark(t, -1, 0, d, true, true);
            if (r < 0) { return -1; }
            if (until >= 0 && date.monoMs() >= until) { return -1; }
            continue;
        }
        if (date.monoMs() < lp.fdsTightUntil && !loopFdsFree(lfd)) {
            loopFdsOut(lp);
            continue;
        }
        lp.fdsOut = false;
        fd = net.accept(lfd, true);
        if (fd >= 0) {
            loopForget(lp, fd);     /* its number may have been closed with close(2) */
            return fd;
        }
        err = os.errno();
        if (err == libc.EMFILE || err == libc.ENFILE || err == LOOP_ENOBUFS || err == libc.ENOMEM) {
            loopFdsOut(lp);
        } else if (err == libc.EAGAIN || err == libc.EWOULDBLOCK) {
            r = loopPark(t, lfd, libc.POLLIN, until, true, false);
            if (r <= 0) { return -1; }
        } else if (err != libc.EINTR && err != LOOP_ECONNABORTED) {
            return -1;
        }
    }
    return -1;
}

/* close(2) fd, and let a coro.accept resting out of fds try again. Can
 * be called anywhere. */
pub i32 close(i32 fd) {
    Loop *lp = loopGet(false);
    /* a coroutine still waiting on it would wait forever: the kernel
     * forgets a closed fd */
    if (lp) {
        loopFire(lp, fd, libc.POLLNVAL);
        loopForget(lp, fd);
    }
    i32 r = libc.close(fd);
    if (!lp || !lp.fdsOut) { return r; }
    lp.fdsOut = false;
    for (i64 i = 0; i < lp.n; i++) {
        LoopTask *t = lp.tasks[i];
        if (t.waiting && !t.ready && t.paused) { loopReady(lp, t, 1); }
    }
    return r;
}

/* Ask the loop to wind down: every coro.waitIdle and coro.accept, now
 * and later, returns -1; plain waits and sleeps go on, so the work in
 * flight finishes, and coro.run returns once every coroutine has ended.
 * Can be called anywhere, also before coro.run. */
pub void stop() {
    Loop *lp = loopGet(true);
    if (lp.stopping) { return; }
    lp.stopping = true;
    for (i64 i = 0; i < lp.n; i++) {
        LoopTask *t = lp.tasks[i];
        if (t.waiting && !t.ready && t.idle) { loopReady(lp, t, -1); }
    }
}

/* true once coro.stop() was called (until coro.run returns). */
pub bool stopping() {
    Loop *lp = loopGet(false);
    return lp && lp.stopping;
}

/* Resume t (lp.batch[lp.batchAt]); take it out of the loop when it
 * ends, also by a throw. */
static void loopResume(Loop *lp, LoopTask *t) {
    t.ready = false;
    t.waiting = false;
    try {
        next(t.co);
    } catch (Error e) {
        loopRemove(lp, t);
        lp.batchAt++;               /* loopRequeue skips it */
        throw(e);
    }
    if (done(t.co)) {
        loopRemove(lp, t);
    } else if (!t.waiting) {
        loopReady(lp, t, 0);        /* a plain coro.yield(): its turn again later */
    }
}

/* The kernel's events of this turn, at most timeout ms of waiting (-1: no
 * limit): each fired fd readies its waiters. */
static void loopPollFallback(Loop *lp, i64 timeout);

static void loopPoll(Loop *lp, i64 timeout) {
    i32 q = loopQueue(lp), n, i;
    if (q < 0) {
        loopPollFallback(lp, timeout);
        return;
    }
#ifdef IS_LINUX
    n = epoll_wait(q, lp.ev, LOOP_MAXEV, (i32)timeout);
#else
    libc.timespec ts;
    ts.tv_sec = timeout / 1000;
    ts.tv_nsec = (timeout % 1000) * 1000000;
    n = kevent(q, null, 0, lp.ev, LOOP_MAXEV, timeout < 0 ? null : &ts);
#endif
    if (n < 0) {
        if (os.errno() != libc.EINTR) {
            throw(ERR_LOOP, "coro.run: waiting for events failed (errno %d)", os.errno());
        }
        return;                     /* a signal: the next turn waits again */
    }
    for (i = 0; i < n; i++) {
#ifdef IS_LINUX
        u8 *e = lp.ev + i * LOOP_EV_SIZE;
        u32 events;
        u64 data;
        mem.copy(&events, e, 4);
        mem.copy(&data, e + LOOP_EV_DATA, 8);
        i32 fd = (i32)data;
        i64 rev = (i64)events;
#else
        LoopKevent *k = (LoopKevent *)(lp.ev + i * LOOP_EV_SIZE);
        i64 rev = k.filter == LOOP_EVFILT_WRITE ? libc.POLLOUT : libc.POLLIN;
        if (k.flags & LOOP_EV_EOF) { rev |= libc.POLLHUP; }
        if (k.flags & LOOP_EV_ERROR) { rev = libc.POLLERR; }
        i32 fd = (i32)k.ident;
#endif
        /* fired with nobody waiting for it: narrow the registration to
         * what the waiters want (or drop it), or a level-triggered queue
         * reports it every turn. A woken waiter usually waits again in a
         * moment, so its registration stays. */
        if (loopFire(lp, fd, rev) == 0 && fd >= 0 && fd < lp.fdwCap && lp.fdw[fd]) {
            LoopFd *f = lp.fdw[fd];
            i64 want = loopWant(f);
            if (want != f.armed) { loopRegister(lp, fd, f, want); }
        }
    }
}

/* loopPoll without a kernel queue: one poll(2) over every fd waited on */
static void loopPollFallback(Loop *lp, i64 timeout) {
    i64 n = 0, i, k;
    if (lp.pfdsCap < lp.fdwCap) {
        lp.pfds = loopGrow(lp.pfds, lp.fdwCap, sizeof(libc.pollfd));
        lp.pfdsCap = lp.fdwCap;
    }
    for (i = 0; i < lp.fdwCap; i++) {
        LoopFd *f = lp.fdw[i];
        if (!f || !f.n) { continue; }
        i16 want = 0;
        for (k = 0; k < f.n; k++) { want |= f.w[k].events; }
        lp.pfds[n].fd = (i32)i;
        lp.pfds[n].events = want;
        lp.pfds[n].revents = 0;
        n++;
    }
    if (libc.poll(lp.pfds, n, timeout) < 0) {
        if (os.errno() != libc.EINTR) {
            throw(ERR_LOOP, "coro.run: poll failed (errno %d)", os.errno());
        }
        return;
    }
    for (i = 0; i < n; i++) {
        if (lp.pfds[i].revents) { loopFire(lp, lp.pfds[i].fd, lp.pfds[i].revents); }
    }
}

/* One turn: wait for the kernel's events (no longer than the nearest
 * deadline, not at all when some are ready already), then resume what is
 * ready: first the ones queued before (new, stopped, woken by close or by
 * an fd that answered at once), then the fired fds, then the waits that
 * timed out, earliest deadline first. */
static void loopTurn(Loop *lp) {
    i64 now = date.monoMs(), timeout = -1, i, j, d, first, nd;
    LoopTask *t;
    if (lp.ndeadline > 0) {
        nd = 0;
        for (i = 0; i < lp.n; i++) {
            t = lp.tasks[i];
            if (!t.waiting || t.ready || t.deadline < 0) { continue; }
            nd++;
            d = t.deadline - now;
            if (d < 0) { d = 0; }
            if (timeout < 0 || d < timeout) { timeout = d; }
        }
        lp.ndeadline = nd;
    }
    if (lp.nready > 0) { timeout = 0; }
    if (lp.nonFd == 0 && timeout < 0) {
        throw(ERR_LOOP, "coro.run: every coroutine waits with no fd and no time limit");
    }
    if (timeout > 0x7FFFFFFF) { timeout = 0x7FFFFFFF; }
    if (lp.nonFd > 0 || timeout > 0) {
        loopPoll(lp, timeout);
    }
    if (lp.ndeadline > 0) {
        now = date.monoMs();
        first = lp.nready;
        for (i = 0; i < lp.n; i++) {
            t = lp.tasks[i];
            if (t.waiting && !t.ready && t.deadline >= 0 && t.deadline <= now) {
                loopReady(lp, t, 0);
                for (j = lp.nready - 1; j > first && lp.ready[j - 1].deadline > t.deadline; j--) {
                    lp.ready[j] = lp.ready[j - 1];
                }
                lp.ready[j] = t;
            }
        }
    }
    /* resume this turn's; what they make ready waits for the next turn */
    LoopTask **b = lp.batch;
    lp.batch = lp.ready;
    lp.ready = b;
    lp.batchN = lp.nready;
    lp.nready = 0;
    for (lp.batchAt = 0; lp.batchAt < lp.batchN; lp.batchAt++) {
        loopResume(lp, lp.batch[lp.batchAt]);
    }
}

/* A throw left loopTurn: the batch's ones not resumed yet go first next
 * time. */
static void loopRequeue(Loop *lp) {
    i64 left = lp.batchN - lp.batchAt, i;
    if (left <= 0) { return; }
    for (i = lp.nready - 1; i >= 0; i--) { lp.ready[i + left] = lp.ready[i]; }
    for (i = 0; i < left; i++) { lp.ready[i] = lp.batch[lp.batchAt + i]; }
    lp.nready += left;
    lp.batchAt = lp.batchN = 0;
}

/* Run this thread's loop until every coroutine coro.go started has
 * ended, then free the loop (a later coro.go starts afresh, not
 * stopping). Called on the thread's own stack: inside a coroutine it
 * throws ERR_LOOP. When a loop coroutine throws, that coroutine is
 * released and coro.run throws the error on; the others stay, and
 * another coro.run carries on with them. */
pub void run() {
    rt.HCFs *fs = rt.Fs();
    if (fs.coroCur) { throw(ERR_LOOP, "coro.run: called inside a coroutine"); }
    Loop *lp = fs.coroLoop;
    if (!lp) { return; }
    if (lp.running) { throw(ERR_LOOP, "coro.run: the loop is already running"); }
    lp.running = true;
    try {
        while (lp.n > 0) { loopTurn(lp); }
    } catch (Error e) {
        loopRequeue(lp);
        lp.running = false;
        throw(e);
    }
    fs.coroLoop = null;
    fs.coroLoopFree = null;
    loopFree(lp);
}
