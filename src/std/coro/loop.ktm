module coro;
import date, libc, net, os, rt;

/* A loop of coroutines over poll(2): coro.go starts one, coro.run runs
 * them until none is left, and inside them coro.wait, coro.sleep and
 * coro.accept pause the coroutine instead of blocking the thread. Each
 * thread has its own loop (kept in its rt.HCFs, made on first use). */

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
#else
const i64 LOOP_ECONNABORTED = 103;
const i64 LOOP_ENOBUFS = 105;
#endif

struct LoopTask {
    Coroutine *co;
    i64 at;              /* its index in Loop.tasks */
    i32 fd;              /* the fd its wait polls, -1: none */
    i16 events;
    i64 deadline;        /* date.monoMs() limit of its wait, -1: none */
    i64 result;          /* what the wait returns */
    bool waiting;        /* paused in a wait */
    bool ready;          /* in Loop.ready, to be resumed with `result` */
    bool idle;           /* stop() ends the wait with -1 */
    bool paused;         /* accept out of fds: close() ends the wait */
};

struct Loop {
    LoopTask **tasks;    /* every coroutine of the loop */
    i64 n, cap;          /* cap: also of ready, batch, fds and who */
    LoopTask **ready;    /* to resume next, in order */
    i64 nready;
    LoopTask **batch;    /* the ones being resumed this turn */
    i64 batchAt, batchN;
    libc.pollfd *fds;
    LoopTask **who;      /* the task of each fds entry */
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
    libc.free(lp.fds);
    libc.free(lp.who);
    libc.free(lp);
}

/* The calling thread's loop; made when `make`, else null if none. */
static Loop *loopGet(bool make) {
    rt.HCFs *fs = rt.Fs();
    Loop *lp = fs.coroLoop;
    if (!lp && make) {
        lp = libc.calloc(1, sizeof(Loop));
        if (!lp) { loopOom(); }
        fs.coroLoop = lp;
        fs.coroLoopFree = &loopFree;
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
        lp.fds = loopGrow(lp.fds, lp.cap, sizeof(libc.pollfd));
        lp.who = loopGrow(lp.who, lp.cap, sizeof(LoopTask *));
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

/* Queue t to be resumed with its wait returning result. */
static void loopReady(Loop *lp, LoopTask *t, i64 result) {
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
    t.fd = fd;
    t.events = events;
    t.deadline = deadline;
    t.idle = idle;
    t.paused = paused;
    t.result = 0;
    t.waiting = true;
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
        if (fd >= 0) { return fd; }
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
    i32 r = libc.close(fd);
    Loop *lp = loopGet(false);
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

/* One turn: a poll() over every wait, then resume what is ready: first
 * the ones queued before (new, stopped, woken by close), then the ready
 * fds, then the waits that timed out, earliest deadline first. */
static void loopTurn(Loop *lp) {
    i64 now = date.monoMs(), timeout = -1, nfds = 0, i, j, d, first;
    LoopTask *t;
    for (i = 0; i < lp.n; i++) {
        t = lp.tasks[i];
        if (!t.waiting || t.ready) { continue; }
        if (t.fd >= 0) {
            lp.fds[nfds].fd = t.fd;
            lp.fds[nfds].events = t.events;
            lp.fds[nfds].revents = 0;
            lp.who[nfds++] = t;
        }
        if (t.deadline >= 0) {
            d = t.deadline - now;
            if (d < 0) { d = 0; }
            if (timeout < 0 || d < timeout) { timeout = d; }
        }
    }
    if (lp.nready > 0) { timeout = 0; }
    if (nfds == 0 && timeout < 0) {
        throw(ERR_LOOP, "coro.run: every coroutine waits with no fd and no time limit");
    }
    if (timeout > 0x7FFFFFFF) { timeout = 0x7FFFFFFF; }
    if (libc.poll(lp.fds, nfds, timeout) < 0) {
        if (os.errno() != libc.EINTR) {
            throw(ERR_LOOP, "coro.run: poll failed (errno %d)", os.errno());
        }
        return;                     /* a signal: the next turn polls again */
    }
    for (i = 0; i < nfds; i++) {
        t = lp.who[i];
        if (lp.fds[i].revents && !t.ready) { loopReady(lp, t, lp.fds[i].revents); }
    }
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
