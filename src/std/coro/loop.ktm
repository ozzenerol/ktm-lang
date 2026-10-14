module coro;
import atomic, cstr, date, libc, mem, net, os, rt;

/* A loop of coroutines: coro.go starts one, coro.run runs them until none
 * is left, and inside them coro.wait, coro.sleep and coro.accept pause the
 * coroutine instead of blocking the thread. Each thread has its own loop
 * (kept in its rt.HCFs, made on first use).
 *
 * On Linux the loop uses io_uring when the kernel allows it (6.1 or later,
 * not blocked by seccomp or a container): coro.read, coro.write and
 * coro.accept go to the ring as RECV, SEND and ACCEPT, a wait on an fd as
 * POLL_ADD, and one io_uring_enter per turn submits them all and waits
 * for their completions. A coroutine is resumed only by its operation's
 * completion, also after a timeout, coro.stop or coro.close cancelled it,
 * so the kernel never writes into a buffer the coroutine has left.
 * KTM_CORO_BACKEND=uring|epoll|kqueue|poll picks a backend; coro.backend()
 * says which one the thread's loop uses.
 *
 * Otherwise the waits on fds go to the kernel's event queue, epoll(7) on
 * Linux and kqueue(2) on macOS, so a turn costs what is ready, not what is open. An
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

/* The loop's backend, chosen when the loop is made (loopQueue) */
const i64 LOOP_BK_POLL = 1;      /* poll(2) over the waiters, every turn */
const i64 LOOP_BK_QUEUE = 2;     /* epoll (Linux) or kqueue (macOS) */
const i64 LOOP_BK_URING = 3;     /* io_uring (Linux) */

/* why an io_uring operation was cancelled: what its caller returns */
const i64 LOOP_WHY_TIMEOUT = 1;
const i64 LOOP_WHY_STOP = 2;
const i64 LOOP_WHY_CLOSE = 3;

#ifdef IS_LINUX
/* io_uring through raw system calls (the same numbers on x86_64 and
 * arm64) and its mmap'd rings: no liburing */
extern "c" i64 syscall(i64 nr, i64 a, i64 b, i64 c, i64 d, i64 e, i64 f);
extern "c" i32 accept4(i32 fd, void *addr, void *addrLen, i32 flags);
const i64 LOOP_SYS_URING_SETUP = 425;
const i64 LOOP_SYS_URING_ENTER = 426;
const i64 LOOP_SYS_URING_REGISTER = 427;
/* SQ and CQ sizes: about 32 KiB per loop thread; a CQ overflow is kept by
 * the kernel (FEAT_NODROP) and handed over at the next enter */
const i64 LOOP_URING_SQ = 256;
const i64 LOOP_URING_CQ = 1024;
const i64 LOOP_URING_SETUP_CQSIZE = 0x8;
const i64 LOOP_URING_SETUP_SINGLE_ISSUER = 0x1000;
const i64 LOOP_URING_SETUP_DEFER_TASKRUN = 0x2000;
const i64 LOOP_URING_FEAT_SINGLE_MMAP = 0x1;
const i64 LOOP_URING_FEAT_NODROP = 0x2;
const i64 LOOP_URING_FEAT_EXT_ARG = 0x100;
const i64 LOOP_URING_ENTER_GETEVENTS = 0x1;
const i64 LOOP_URING_ENTER_EXT_ARG = 0x8;
const i64 LOOP_URING_OFF_SQ_RING = 0;
const i64 LOOP_URING_OFF_CQ_RING = 0x8000000;
const i64 LOOP_URING_OFF_SQES = 0x10000000;
const i64 LOOP_URING_REGISTER_PROBE = 8;
const i64 LOOP_OP_POLL_ADD = 6;
const i64 LOOP_OP_ACCEPT = 13;
const i64 LOOP_OP_ASYNC_CANCEL = 14;
const i64 LOOP_OP_READ = 22;
const i64 LOOP_OP_WRITE = 23;
const i64 LOOP_OP_SEND = 26;
const i64 LOOP_OP_RECV = 27;
const i64 LOOP_ECANCELED = 125;
const i64 LOOP_ETIME = 62;
const i64 LOOP_EBUSY = 16;
const i64 LOOP_MSG_WAITALL = 0x100;
const i64 LOOP_CQE_F_SOCK_NONEMPTY = 0x4;
const i64 LOOP_TCP_INFO = 11;
/* multishot RECV into a ring of provided buffers (coro.recvMulti) */
const i64 LOOP_URING_REGISTER_PBUF_RING = 22;
const i64 LOOP_URING_UNREGISTER_PBUF_RING = 23;
const i64 LOOP_RECV_MULTISHOT = 2;       /* sqe.ioprio */
const i64 LOOP_IOSQE_BUFFER_SELECT = 32; /* sqe.flags */
const i64 LOOP_CQE_F_BUFFER = 1;
const i64 LOOP_CQE_F_MORE = 2;
const i64 LOOP_PBUF_N = 1024;            /* buffers per loop (a power of two) */
const i64 LOOP_PBUF_SIZE = 2048;         /* bytes each: requests are small */
/* coro.accept with io_uring takes one client per ACCEPT, a turn of the loop
 * each. A queue of clients that stays (a new connection per request) is
 * fine that way: each ACCEPT completes at once, and taking them faster
 * costs 20% of the requests per CPU-second (the empty queue's wake-ups).
 * A burst is not (1024 clients connecting at once: the last one waited
 * over a second, bench/http). So after this many ACCEPTs in a row that
 * left clients queued (kernel 6.10+ says so), it asks the queue's length
 * (TCP_INFO), and from this many it takes them with plain accepts until
 * none is left. Measured on bench/http: new connection per request, 64
 * clients: 47.6k requests per CPU-second, as before (taking every queue
 * at once: 37.9k); keep-alive, 1024 clients: p99 5.8 ms (before: 43 ms) */
const i64 LOOP_ACCEPT_RUN = 8;
const i64 LOOP_ACCEPT_QUEUE = 64;
const i64 LOOP_MSG_NOSIGNAL = 0x4000;
const i64 LOOP_SOCK_NONBLOCK = 0x800;
const i64 LOOP_SOCK_CLOEXEC = 0x80000;
const i64 LOOP_MAP_SHARED = 0x1;
const i64 LOOP_MAP_POPULATE = 0x8000;
#endif
/* How long coro.read goes on reading without a turn of the loop while
 * each read fills its buffer (a busy stream), so it doesn't starve the
 * loop's other coroutines: one sharing a loop with a saturating stream
 * waits about twice this at most (test 636). Measured on the v0.13.0
 * echo benchmark (bench/tcp-echo, 64 KiB streams at 16 and 64
 * connections, MB per server CPU-second; Go: 3360-3400):
 *   32 reads, the old cap (about 0.6 ms):  2870-2900
 *   1 ms:                                  3070-3130
 *   2 ms:                                  3410-3490, ties Go
 *   4 ms:                                  3670-3780, +9-11% (7.3-7.5 GB/s
 *                                          against Go's 6.5-6.6)
 *   8 ms:                                  3710-3790, no further gain
 * 4 ms is the knee, and its worst wait for another coroutine (about 8 ms)
 * is still tighter than Go's 10 ms preemption tick. Requests (reads that
 * don't fill the buffer) never use it, so they are unaffected. */
const i64 LOOP_READ_BUDGET_NS = 4000000;

/* io_uring: after a read of at least this much (a stream), coro.read
 * waits with POLL_ADD and then reads what has piled up, as the other
 * backends do; a RECV would take each bit as it comes, which keeps the
 * kernel's receive buffer small (more segments and ACKs: 7% fewer bytes
 * per CPU-second). Small reads (requests) keep the RECV. */
const i64 LOOP_READ_BIG = 16384;

/* The coroutines waiting on one fd, and what it is registered for */
struct LoopFd {
    LoopTask **w;
    i64 n, cap;
    i64 armed;           /* POLLIN/POLLOUT it is registered for, 0: none */
    bool full;           /* coro.read's last read filled its buffer */
    bool big;            /* and was at least LOOP_READ_BIG */
    i64 burstNs;         /* when the reads that didn't wait began */
    bool notSock;        /* io_uring: not a socket, so READ/WRITE, not RECV/SEND */
    bool more;           /* io_uring: a listener with a burst queued: plain accepts */
    i64 queuedRun;       /* io_uring: ACCEPTs in a row that left clients queued */
    LoopStream *stream;  /* io_uring: coro.recvMulti's RECV, null: none */
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
    bool op;             /* io_uring: its operation is in flight */
    bool cancelled;      /* io_uring: and an ASYNC_CANCEL was sent for it */
    i64 why;             /* io_uring: LOOP_WHY_* of that cancel */
    i64 cqeFlags;        /* io_uring: the flags of its operation's completion */
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
    i64 backend;         /* LOOP_BK_*, 0 until loopQueue chose */
    /* io_uring (LOOP_BK_URING): the ring fd and its three mappings */
    i32 ring;
    u8 *sqMap, *cqMap, *sqes;
    i64 sqMapLen, cqMapLen, sqesLen;
    u32 *sqHead, *sqTail, *cqHead, *cqTail;
    u32 sqMask, cqMask, sqEntries;
    u32 sqLocal;         /* the SQ tail as filled so far */
    u8 *cqes;
    /* coro.recvMulti's provided buffers: LOOP_PBUF_N of LOOP_PBUF_SIZE */
    u8 *pbRing;          /* the ring the kernel takes them from */
    u8 *pbMem;
    u32 pbTail;
    i64 pbState;         /* 0: not tried yet, 1: registered, -1: unsupported */
};

/* A queued piece of a multishot RECV: buffer bid, len bytes, off taken */
struct LoopChunk {
    i64 bid, len, off;
};

/* coro.recvMulti on one fd: its RECV's completion's user_data is this
 * with 2 in the low bits (a LoopTask's are 0) */
struct LoopStream {
    LoopChunk *q;        /* received, not read yet: a ring of cap */
    i64 head, n, cap;
    bool armed;          /* the RECV is in the kernel */
    bool eof;
    bool nobufs;         /* it ended out of buffers: one plain read next */
    i64 err;             /* -errno it ended with, 0: none */
    LoopTask *waiter;    /* coro.read waiting for data */
    bool abandoned;      /* coro.close let it go: freed when it ends */
    i32 fd;
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

#ifdef IS_LINUX
static void loopUringFree(Loop *lp);
#endif

static void loopFree(void *p) {
    Loop *lp = p;
#ifdef IS_LINUX
    if (lp.backend == LOOP_BK_URING) { loopUringFree(lp); }
#endif
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

#ifdef IS_LINUX
static bool loopUringSetup(Loop *lp);
#endif

/* The backend KTM_CORO_BACKEND asks for (LOOP_BK_*), 0 for the default */
static i64 loopBackendWanted() {
    u8 *v = (u8 *)libc.getenv("KTM_CORO_BACKEND");
    if (!v) { return 0; }
    if (!cstr.cmp(v, "uring")) { return LOOP_BK_URING; }
    if (!cstr.cmp(v, "epoll") || !cstr.cmp(v, "kqueue")) { return LOOP_BK_QUEUE; }
    if (!cstr.cmp(v, "poll")) { return LOOP_BK_POLL; }
    return 0;
}

/* Choose the loop's backend when it is made: io_uring on Linux when the
 * kernel allows it, else the kernel's event queue (epoll, kqueue), else
 * poll(2) (no fd for a queue). KTM_CORO_BACKEND picks one; one that
 * can't be had gives the next one down. The queue's fd, -1 for io_uring
 * and poll(2). */
static i32 loopQueue(Loop *lp) {
    if (lp.backend) { return lp.q; }
    i64 want = loopBackendWanted();
    lp.ev = libc.malloc(LOOP_MAXEV * LOOP_EV_SIZE);
    if (!lp.ev) { loopOom(); }
#ifdef IS_LINUX
    if ((want == 0 || want == LOOP_BK_URING) && loopUringSetup(lp)) {
        lp.backend = LOOP_BK_URING;
        return -1;
    }
#endif
    if (want == LOOP_BK_POLL) {
        lp.backend = LOOP_BK_POLL;
        return -1;
    }
#ifdef IS_LINUX
    lp.q = epoll_create1(LOOP_EPOLL_CLOEXEC);
#else
    lp.q = kqueue();
    if (lp.q >= 0) { libc.fcntl(lp.q, libc.F_SETFD, libc.FD_CLOEXEC); }
#endif
    lp.backend = lp.q >= 0 ? LOOP_BK_QUEUE : LOOP_BK_POLL;
    return lp.q;
}

/* The backend of the calling thread's loop (made now if there is none):
 * "uring" (io_uring, Linux), "epoll" (Linux), "kqueue" (macOS) or "poll"
 * (poll(2), when no queue could be made or KTM_CORO_BACKEND=poll). */
pub u8[] backend() {
    Loop *lp = loopGet(true);
    if (lp.backend == LOOP_BK_URING) { return "uring"; }
    if (lp.backend == LOOP_BK_QUEUE) {
#ifdef IS_LINUX
        return "epoll";
#else
        return "kqueue";
#endif
    }
    return "poll";
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
        lp.fdw[fd].notSock = false;
        lp.fdw[fd].more = false;
    }
}

/* t waits on fd for events: one more waiter of fd, and fd armed. */
static LoopFd *loopFdOf(Loop *lp, i32 fd);

static void loopAddWaiter(Loop *lp, LoopTask *t, i32 fd) {
    LoopFd *f = loopFdOf(lp, fd);
    if (f.n == f.cap) {
        f.cap = f.cap ? f.cap * 2 : 2;
        f.w = loopGrow(f.w, f.cap, sizeof(LoopTask *));
    }
    f.w[f.n++] = t;
    t.onFd = true;
    lp.nonFd++;
}

static void loopWaitFd(Loop *lp, LoopTask *t, i32 fd) {
    loopAddWaiter(lp, t, fd);
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
#ifdef IS_LINUX
static i64 loopOp(Loop *lp, LoopTask *t, i64 opcode, i32 fd, u64 addr, i64 len, i64 opFlags,
                  u64 off, i64 deadline, bool idle);
#endif

static i64 loopPark(LoopTask *t, i32 fd, i16 events, i64 deadline, bool idle, bool paused) {
    Loop *lp = loopGet(true);
#ifdef IS_LINUX
    if (lp.backend == LOOP_BK_URING && fd >= 0) {
        /* POLL_ADD; a cancel answers as the other backends do: 0 for the
         * deadline, -1 for coro.stop, POLLNVAL for coro.close */
        i64 r = loopOp(lp, t, LOOP_OP_POLL_ADD, fd, 0, 0, (u16)events, 0, deadline, idle);
        if (r > 0) { return r; }
        if (r == -LOOP_ECANCELED) {
            if (t.why == LOOP_WHY_TIMEOUT) { return 0; }
            if (t.why == LOOP_WHY_STOP) { return -1; }
        }
        return libc.POLLNVAL;
    }
#endif
    t.cancelled = false;            /* io_uring: from an operation before */
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

#ifdef IS_LINUX
/* ---- io_uring ---- */

/* An io_uring for the loop: SINGLE_ISSUER and DEFER_TASKRUN (only this
 * thread uses it, and completions are handed over at its enter), and a
 * kernel that has every operation used here, NODROP and EXT_ARG; false
 * (and nothing kept) otherwise: no io_uring (ENOSYS), seccomp (EPERM),
 * RLIMIT_MEMLOCK (ENOMEM), a kernel before 6.1 (EINVAL). */
static bool loopUringSetup(Loop *lp) {
    u32 p[30];               /* struct io_uring_params, 120 bytes */
    u8 probe[16 + 64 * 8];   /* struct io_uring_probe with 64 ops */
    i64 need[7] = {LOOP_OP_POLL_ADD, LOOP_OP_ACCEPT, LOOP_OP_ASYNC_CANCEL,
                   LOOP_OP_READ, LOOP_OP_WRITE, LOOP_OP_SEND, LOOP_OP_RECV};
    mem.set(p, 0, sizeof(p));
    p[1] = LOOP_URING_CQ;
    p[2] = LOOP_URING_SETUP_CQSIZE | LOOP_URING_SETUP_SINGLE_ISSUER | LOOP_URING_SETUP_DEFER_TASKRUN;
    i64 fd = syscall(LOOP_SYS_URING_SETUP, LOOP_URING_SQ, (i64)p, 0, 0, 0, 0);
    if (fd < 0) { return false; }
    i32 ring = (i32)fd;
    libc.fcntl(ring, libc.F_SETFD, libc.FD_CLOEXEC);
    i64 feat = p[5];
    bool ok = (feat & LOOP_URING_FEAT_NODROP) && (feat & LOOP_URING_FEAT_EXT_ARG);
    mem.set(probe, 0, sizeof(probe));
    if (ok && syscall(LOOP_SYS_URING_REGISTER, ring, LOOP_URING_REGISTER_PROBE, (i64)probe, 64, 0, 0) < 0) {
        ok = false;
    }
    for (i64 i = 0; ok && i < 7; i++) {
        u16 flags = *(u16 *)(probe + 16 + need[i] * 8 + 2);
        if (need[i] > probe[0] || !(flags & 1)) { ok = false; }   /* IO_URING_OP_SUPPORTED */
    }
    if (!ok) {
        libc.close(ring);
        return false;
    }
    i64 sqLen = p[16] + p[0] * 4;          /* sq_off.array + sq_entries u32s */
    i64 cqLen = p[25] + p[1] * 16;         /* cq_off.cqes + cq_entries cqes */
    bool single = feat & LOOP_URING_FEAT_SINGLE_MMAP;
    if (single && cqLen > sqLen) { sqLen = cqLen; }
    i32 prot = CORO_PROT_READ | CORO_PROT_WRITE;
    i32 flags = LOOP_MAP_SHARED | LOOP_MAP_POPULATE;
    u8 *sq = libc.mmap(null, sqLen, prot, flags, ring, LOOP_URING_OFF_SQ_RING);
    u8 *cq = (u8 *)-1, *sqes = (u8 *)-1;
    if ((i64)sq != -1) {
        cq = single ? sq : libc.mmap(null, cqLen, prot, flags, ring, LOOP_URING_OFF_CQ_RING);
        sqes = libc.mmap(null, p[0] * 64, prot, flags, ring, LOOP_URING_OFF_SQES);
    }
    if ((i64)sq == -1 || (i64)cq == -1 || (i64)sqes == -1) {
        if ((i64)sqes != -1) { libc.munmap(sqes, p[0] * 64); }
        if ((i64)cq != -1 && cq != sq) { libc.munmap(cq, cqLen); }
        if ((i64)sq != -1) { libc.munmap(sq, sqLen); }
        libc.close(ring);
        return false;
    }
    lp.ring = ring;
    lp.sqMap = sq;
    lp.sqMapLen = sqLen;
    lp.cqMap = cq;
    lp.cqMapLen = single ? 0 : cqLen;
    lp.sqes = sqes;
    lp.sqesLen = p[0] * 64;
    lp.sqHead = (u32 *)(sq + p[10]);
    lp.sqTail = (u32 *)(sq + p[11]);
    lp.sqMask = *(u32 *)(sq + p[12]);
    lp.sqEntries = *(u32 *)(sq + p[13]);
    lp.cqHead = (u32 *)(cq + p[20]);
    lp.cqTail = (u32 *)(cq + p[21]);
    lp.cqMask = *(u32 *)(cq + p[22]);
    lp.cqes = cq + p[25];
    u32 *array = (u32 *)(sq + p[16]);
    for (u32 i = 0; i < lp.sqEntries; i++) { array[i] = i; }   /* SQE i at slot i, for good */
    lp.sqLocal = *lp.sqTail;
    return true;
}

/* io_uring_enter: submit what was queued and, when `wait`, take the
 * completions (at least one unless timeout is 0, for at most timeout ms;
 * -1: no limit). The system call's result; -1 with errno on an error. */
static i64 loopUringEnter(Loop *lp, i64 timeout, bool wait) {
    i64 n = (i64)((lp.sqLocal - atomic.load(lp.sqHead)) & 0xFFFFFFFF);
    i64 flags = 0, min = 0, arg = 0, argSize = 0;
    i64 ts[2];
    i64 ga[3];               /* struct io_uring_getevents_arg */
    if (wait) {
        flags = LOOP_URING_ENTER_GETEVENTS;
        if (timeout != 0) { min = 1; }
        if (timeout > 0) {
            ts[0] = timeout / 1000;
            ts[1] = (timeout % 1000) * 1000000;
            ga[0] = 0;
            ga[1] = 0;
            ga[2] = (i64)ts;
            flags |= LOOP_URING_ENTER_EXT_ARG;
            arg = (i64)ga;
            argSize = 24;
        }
    } else if (n == 0) {
        return 0;
    }
    return syscall(LOOP_SYS_URING_ENTER, lp.ring, n, min, flags, arg, argSize);
}

/* A zeroed SQE to fill; a full SQ is submitted first */
static u8 *loopSqe(Loop *lp) {
    while (((lp.sqLocal - atomic.load(lp.sqHead)) & 0xFFFFFFFF) >= lp.sqEntries) {
        if (loopUringEnter(lp, 0, false) < 0 && os.errno() != libc.EINTR && os.errno() != libc.EAGAIN &&
            os.errno() != LOOP_EBUSY) {
            throw(ERR_LOOP, "coro: io_uring submission failed (errno %d)", os.errno());
        }
    }
    u8 *e = lp.sqes + (i64)(lp.sqLocal & lp.sqMask) * 64;
    mem.set(e, 0, 64);
    return e;
}

/* Fill the SQE e (struct io_uring_sqe) and hand it to the kernel's ring */
static void loopSqePut(Loop *lp, u8 *e, i64 opcode, i32 fd, u64 addr, i64 len, i64 opFlags,
                       u64 off, u64 userData) {
    *e = (u8)opcode;
    *(i32 *)(e + 4) = fd;
    *(u64 *)(e + 8) = off;
    *(u64 *)(e + 16) = addr;
    *(u32 *)(e + 24) = (u32)len;
    *(u32 *)(e + 28) = (u32)opFlags;
    *(u64 *)(e + 32) = userData;
    lp.sqLocal++;
    atomic.store(lp.sqTail, lp.sqLocal);
}

/* Every completion there is: each resumes its operation's task with the
 * CQE's res. A cancel's own completion (user_data 0) is dropped. */
static void loopUringReap(Loop *lp) {
    u32 head = *lp.cqHead, tail = atomic.load(lp.cqTail);
    while (head != tail) {
        u8 *c = lp.cqes + (i64)(head & lp.cqMask) * 16;
        u64 ud = *(u64 *)c;
        i32 res = *(i32 *)(c + 8);
        u32 cflags = *(u32 *)(c + 12);
        head++;
        if ((ud & 7) == 2) {
            loopStreamCqe(lp, (LoopStream *)(ud & ~(u64)7), res, cflags);
        } else if (ud) {
            LoopTask *t = (LoopTask *)ud;
            t.op = false;
            t.cqeFlags = cflags;
            loopReady(lp, t, res);
        }
    }
    atomic.store(lp.cqHead, head);
}

/* Submit t's operation and pause until its completion: the CQE's res (the
 * bytes, the fd, the revents, or -errno; -ECANCELED after loopCancel, with
 * t.why saying why). The deadline (if >= 0) cancels it, and so does
 * coro.stop when idle. */
static i64 loopOp(Loop *lp, LoopTask *t, i64 opcode, i32 fd, u64 addr, i64 len, i64 opFlags,
                  u64 off, i64 deadline, bool idle) {
    u8 *e = loopSqe(lp);
    loopSqePut(lp, e, opcode, fd, addr, len, opFlags, off, (u64)t);
    t.fd = fd;
    t.events = 0;
    t.deadline = deadline;
    t.idle = idle;
    t.paused = false;
    t.result = 0;
    t.waiting = true;
    t.op = true;
    t.cancelled = false;
    t.why = 0;
    if (deadline >= 0) { lp.ndeadline++; }
    loopAddWaiter(lp, t, fd);       /* so coro.close finds it */
    yield();
    return t.result;
}

/* Ask the kernel to cancel t's operation (once); t resumes with its
 * completion, -ECANCELED or what it did before the cancel reached it */
static void loopCancel(Loop *lp, LoopTask *t, i64 why) {
    if (!t.op || t.cancelled) { return; }
    t.cancelled = true;
    t.why = why;
    u8 *e = loopSqe(lp);
    loopSqePut(lp, e, LOOP_OP_ASYNC_CANCEL, -1, (u64)t, 0, 0, 0, 0);
}

/* The thread's loop goes away with operations in flight: cancel them and
 * wait for their completions (a second or so at most), so the kernel
 * writes into no stack after it is unmapped; then drop the ring. */
static void loopUringFree(Loop *lp) {
    for (i64 round = 0; round < 20; round++) {
        bool any = false;
        for (i64 i = 0; i < lp.n; i++) {
            if (lp.tasks[i].op) {
                loopCancel(lp, lp.tasks[i], LOOP_WHY_STOP);
                any = true;
            }
        }
        if (!any) { break; }
        loopUringEnter(lp, 50, true);
        loopUringReap(lp);
    }
    if (lp.pbState == 1) {
        u64 breg[5];
        mem.set(breg, 0, sizeof(breg));
        syscall(LOOP_SYS_URING_REGISTER, lp.ring, LOOP_URING_UNREGISTER_PBUF_RING, (i64)breg, 1, 0, 0);
        libc.munmap(lp.pbRing, LOOP_PBUF_N * 16);
        libc.munmap(lp.pbMem, LOOP_PBUF_N * LOOP_PBUF_SIZE);
    }
    libc.munmap(lp.sqes, lp.sqesLen);
    if (lp.cqMapLen) { libc.munmap(lp.cqMap, lp.cqMapLen); }
    libc.munmap(lp.sqMap, lp.sqMapLen);
    libc.close(lp.ring);
}
#endif

/* In a loop coroutine: read what is there from the non-blocking fd into
 * buf, pausing the coroutine until there is something (or ms passed;
 * ms < 0: no limit). Returns the bytes read (at most buf.len), 0 at end
 * of file or when the peer closed, -1 on an error or the timeout
 * (os.errno() ETIMEDOUT). It reads at once when the last read filled
 * its buffer (more is likely waiting, as in a stream), and waits first
 * otherwise (the next request isn't there yet): either way a coroutine
 * per connection costs no more system calls than it must. Reads skip
 * the wait for 4 ms at most (LOOP_READ_BUDGET_NS), then wait once, so the
 * loop's other coroutines get their turn. */
pub i64 read(i32 fd, u8[] buf, i64 ms=-1) {
    LoopTask *t = loopSelf("coro.read");
    Loop *lp = loopGet(true);
    LoopFd *f = fd >= 0 ? loopFdOf(lp, fd) : null;
    if (!f || buf.len == 0) {
        if (!f) { os.setErrno(libc.EBADF); return -1; }
        return 0;
    }
    i64 until = loopDeadline(ms);
#ifdef IS_LINUX
    if (lp.backend == LOOP_BK_URING && f.stream) {
        i64 rs = loopStreamRead(lp, t, f, buf, until);
        if (rs != -2) { return rs; }
        f = lp.fdw[fd];             /* -2: out of buffers, a plain RECV this time */
    }
    if (lp.backend == LOOP_BK_URING) {
        /* RECV straight into buf: the kernel reads what is there at once,
         * or when it comes; READ for what isn't a socket (a pipe). After a
         * read that filled its buffer (a stream) a plain recv first is
         * cheaper: more is usually there already (measured: one stream
         * 4.3-4.8 GB/s with it, 3.6 without). */
        i64 len = buf.len > 0x7FFFFFFF ? 0x7FFFFFFF : buf.len;
        if (f.full && !f.notSock && date.monoNs() - f.burstNs < LOOP_READ_BUDGET_NS) {
            i64 n0 = libc.recv(fd, buf.ptr, len, 0);
            if (n0 >= 0) {
                f.full = n0 == len;
                f.big = n0 >= LOOP_READ_BIG;
                return n0;
            }
        }
        if (f.big && !f.notSock) {
            /* a stream: wait, then read what piled up */
            while (true) {
                if (loopPark(t, fd, libc.POLLIN, until, false, false) == 0) {
                    os.setErrno(libc.ETIMEDOUT);
                    return -1;
                }
                i64 n1 = libc.recv(fd, buf.ptr, len, 0);
                if (n1 >= 0) {
                    f = lp.fdw[fd];
                    f.full = n1 == len;
                    f.big = n1 >= LOOP_READ_BIG;
                    f.burstNs = date.monoNs();
                    return n1;
                }
                i32 e1 = os.errno();
                if (e1 != libc.EAGAIN && e1 != libc.EWOULDBLOCK && e1 != libc.EINTR) { return -1; }
            }
        }
        while (true) {
            f = lp.fdw[fd];
            i64 r = f.notSock
                ? loopOp(lp, t, LOOP_OP_READ, fd, (u64)buf.ptr, len, 0, (u64)-1, until, false)
                : loopOp(lp, t, LOOP_OP_RECV, fd, (u64)buf.ptr, len, 0, 0, until, false);
            if (r >= 0) {
                f = lp.fdw[fd];
                f.full = r == len;
                f.big = r >= LOOP_READ_BIG;
                f.burstNs = date.monoNs();
                return r;
            }
            if (r == -LOOP_ECANCELED) {
                os.setErrno(t.why == LOOP_WHY_TIMEOUT ? libc.ETIMEDOUT : libc.EBADF);
                return -1;
            }
            if (r == -LOOP_ENOTSOCK && !f.notSock) {
                f.notSock = true;
                continue;
            }
            if (r == -libc.EAGAIN || r == -libc.EINTR) {
                if (loopPark(t, fd, libc.POLLIN, until, false, false) == 0) {
                    os.setErrno(libc.ETIMEDOUT);
                    return -1;
                }
                continue;
            }
            os.setErrno((i32)(-r));
            return -1;
        }
    }
#endif
    bool tryFirst = f.full && date.monoNs() - f.burstNs < LOOP_READ_BUDGET_NS;
    bool waited = !tryFirst;
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
            if (waited) { f.burstNs = date.monoNs(); }   /* a new burst begins */
            return n;
        }
        i32 err = os.errno();
        if (err != libc.EAGAIN && err != libc.EWOULDBLOCK && err != libc.EINTR) { return -1; }
        lp.fdw[fd].full = false;
        waited = true;
    }
    return -1;
}

#ifdef IS_LINUX
/* buffer bid back to the kernel's ring */
static void loopPbufPut(Loop *lp, i64 bid) {
    u8 *e = lp.pbRing + (i64)(lp.pbTail & (LOOP_PBUF_N - 1)) * 16;
    *(u64 *)e = (u64)(lp.pbMem + bid * LOOP_PBUF_SIZE);
    *(u32 *)(e + 8) = (u32)LOOP_PBUF_SIZE;
    *(u16 *)(e + 12) = (u16)bid;
    lp.pbTail++;
    /* the tail is the u16 at 14, sharing a u32 with entry 0's bid at 12:
     * one release store of both, that bid as it is */
    u32 *word = (u32 *)(lp.pbRing + 12);
    atomic.store(word, (i64)((*word & 0xFFFF) | ((lp.pbTail & 0xFFFF) << 16)));
}

/* The provided buffers registered (once per loop): false when the kernel
 * can't (before 5.19) */
static bool loopPbufSetup(Loop *lp) {
    if (lp.pbState) { return lp.pbState == 1; }
    lp.pbState = -1;
    i32 prot = CORO_PROT_READ | CORO_PROT_WRITE;
    u8 *ring = libc.mmap(null, LOOP_PBUF_N * 16, prot, CORO_MAP_PRIVATE | CORO_MAP_ANONYMOUS, -1, 0);
    if ((i64)ring == -1) { return false; }
    u8 *bufs = libc.mmap(null, LOOP_PBUF_N * LOOP_PBUF_SIZE, prot, CORO_MAP_PRIVATE | CORO_MAP_ANONYMOUS, -1, 0);
    if ((i64)bufs == -1) {
        libc.munmap(ring, LOOP_PBUF_N * 16);
        return false;
    }
    u64 breg[5];              /* struct io_uring_buf_reg: group 0 */
    mem.set(breg, 0, sizeof(breg));
    breg[0] = (u64)ring;
    breg[1] = (u64)LOOP_PBUF_N;
    if (syscall(LOOP_SYS_URING_REGISTER, lp.ring, LOOP_URING_REGISTER_PBUF_RING, (i64)breg, 1, 0, 0) < 0) {
        libc.munmap(bufs, LOOP_PBUF_N * LOOP_PBUF_SIZE);
        libc.munmap(ring, LOOP_PBUF_N * 16);
        return false;
    }
    lp.pbRing = ring;
    lp.pbMem = bufs;
    lp.pbTail = 0;
    for (i64 b = 0; b < LOOP_PBUF_N; b++) { loopPbufPut(lp, b); }
    lp.pbState = 1;
    return true;
}

static void loopStreamArm(Loop *lp, i32 fd, LoopStream *st) {
    u8 *e = loopSqe(lp);
    e[1] = (u8)LOOP_IOSQE_BUFFER_SELECT;
    *(u16 *)(e + 2) = (u16)LOOP_RECV_MULTISHOT;
    *(u16 *)(e + 40) = 0;   /* buffer group 0 */
    loopSqePut(lp, e, LOOP_OP_RECV, fd, 0, 0, 0, 0, (u64)st | 2);
    st.armed = true;
}

/* A completion of a stream's RECV */
static void loopStreamCqe(Loop *lp, LoopStream *st, i64 res, u32 cflags) {
    bool more = (cflags & LOOP_CQE_F_MORE) != 0;
    if (res > 0 && (cflags & LOOP_CQE_F_BUFFER)) {
        i64 bid = (i64)(cflags >> 16);
        if (st.abandoned) {
            loopPbufPut(lp, bid);
        } else {
            if (st.n == st.cap) {
                /* grow the ring, its items in order from 0 */
                i64 cap = st.cap ? st.cap * 2 : 4;
                LoopChunk *q = libc.malloc(cap * sizeof(LoopChunk));
                if (!q) { loopOom(); }
                for (i64 k = 0; k < st.n; k++) { q[k] = st.q[(st.head + k) % st.cap]; }
                libc.free(st.q);
                st.q = q;
                st.head = 0;
                st.cap = cap;
            }
            LoopChunk *c = &st.q[(st.head + st.n) % st.cap];
            c.bid = bid;
            c.len = res;
            c.off = 0;
            st.n++;
        }
    } else if (res == 0) {
        st.eof = true;
    } else if (res == -LOOP_ENOBUFS) {
        st.nobufs = true;
    } else if (res < 0 && res != -LOOP_ECANCELED) {
        st.err = res;
    }
    if (!more) {
        st.armed = false;
        if (st.abandoned) {
            libc.free(st.q);
            libc.free(st);
            return;
        }
    }
    if (st.waiter && !st.abandoned) {
        LoopTask *wt = st.waiter;
        st.waiter = null;
        if (wt.waiting && !wt.ready) { loopReady(lp, wt, 1); }
    }
}

/* coro.read on a stream: what is queued, or wait for it. -2: read this
 * one with a plain RECV (it ran out of buffers) */
static i64 loopStreamRead(Loop *lp, LoopTask *t, LoopFd *f, u8[] buf, i64 until) {
    LoopStream *st = f.stream;
    while (true) {
        if (st.n > 0) {
            i64 got = 0;
            while (st.n > 0 && got < buf.len) {
                LoopChunk *c = &st.q[st.head];
                i64 take = c.len - c.off;
                if (take > buf.len - got) { take = buf.len - got; }
                mem.copy(buf.ptr + got, lp.pbMem + c.bid * LOOP_PBUF_SIZE + c.off, take);
                c.off += take;
                got += take;
                if (c.off == c.len) {
                    loopPbufPut(lp, c.bid);
                    st.head = (st.head + 1) % st.cap;
                    st.n--;
                }
            }
            return got;
        }
        if (st.err) {
            os.setErrno((i32)(-st.err));
            st.err = 0;
            return -1;
        }
        if (st.eof) { return 0; }
        if (st.nobufs && !st.armed) {
            st.nobufs = false;
            return -2;
        }
        if (!st.armed) { loopStreamArm(lp, st.fd, st); }
        st.waiter = t;
        t.fd = -1;
        t.events = 0;
        t.deadline = until;
        t.idle = false;
        t.paused = false;
        t.result = 0;
        t.waiting = true;
        t.op = false;
        t.cancelled = false;
        t.why = 0;
        if (until >= 0) { lp.ndeadline++; }
        yield();
        if (t.result < 0) {          /* coro.close: st may be gone */
            os.setErrno(libc.EBADF);
            return -1;
        }
        st.waiter = null;
        if (t.result == 0) {         /* the deadline */
            os.setErrno(libc.ETIMEDOUT);
            return -1;
        }
    }
    return -1;
}

/* coro.close of a stream's fd: its pieces back, its waiter woken, its
 * RECV cancelled (freed when that ends) */
static void loopStreamClose(Loop *lp, LoopFd *f) {
    LoopStream *st = f.stream;
    f.stream = null;
    while (st.n > 0) {
        loopPbufPut(lp, st.q[st.head].bid);
        st.head = (st.head + 1) % st.cap;
        st.n--;
    }
    if (st.waiter) {
        LoopTask *wt = st.waiter;
        st.waiter = null;
        if (wt.waiting && !wt.ready) { loopReady(lp, wt, -1); }
    }
    if (st.armed) {
        st.abandoned = true;
        u8 *e = loopSqe(lp);
        loopSqePut(lp, e, LOOP_OP_ASYNC_CANCEL, -1, (u64)st | 2, 0, 0, 0, 0);
    } else {
        libc.free(st.q);
        libc.free(st);
    }
}
#endif

/* From now on coro.read on the socket fd takes what the loop's one
 * long-lived receive collected (io_uring: a multishot RECV into the
 * loop's shared buffers, no new RECV per read: for connections that
 * read many small messages, like an HTTP server's). False, and nothing
 * changes, where the loop can't. coro.close ends it. */
pub bool recvMulti(i32 fd) {
#ifdef IS_LINUX
    Loop *lp = loopGet(true);
    if (lp.backend != LOOP_BK_URING || fd < 0 || !loopPbufSetup(lp)) { return false; }
    LoopFd *f = loopFdOf(lp, fd);
    if (f.stream || f.notSock) { return f.stream != null; }
    LoopStream *st = libc.calloc(1, sizeof(LoopStream));
    if (!st) { loopOom(); }
    st.fd = fd;
    f.stream = st;
    return true;
#else
    return false;
#endif
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
#ifdef IS_LINUX
    Loop *lp = loopGet(true);
    if (lp.backend == LOOP_BK_URING && fd >= 0) {
        /* SEND (all of it: MSG_WAITALL; a short one sends the rest), or
         * WRITE for what isn't a socket (a pipe) */
        LoopFd *f = loopFdOf(lp, fd);
        /* a plain send first: the socket usually has room, and SEND would
         * cost the coroutine a turn of the loop for its completion */
        if (!f.notSock) {
            while (off < data.len) {
                i64 w0 = libc.send(fd, data.ptr + off, data.len - off, LOOP_MSG_NOSIGNAL | libc.MSG_DONTWAIT);
                if (w0 <= 0) { break; }
                off += w0;
            }
            if (off == data.len) { return data.len; }
        }
        while (off < data.len) {
            i64 len = data.len - off;
            if (len > 0x7FFFFFFF) { len = 0x7FFFFFFF; }
            f = lp.fdw[fd];
            i64 r = f.notSock
                ? loopOp(lp, t, LOOP_OP_WRITE, fd, (u64)(data.ptr + off), len, 0, (u64)-1, until, false)
                : loopOp(lp, t, LOOP_OP_SEND, fd, (u64)(data.ptr + off), len,
                         LOOP_MSG_NOSIGNAL | LOOP_MSG_WAITALL, 0, until, false);
            if (r > 0) {
                off += r;
                continue;
            }
            if (r == -LOOP_ECANCELED) {
                os.setErrno(t.why == LOOP_WHY_TIMEOUT ? libc.ETIMEDOUT : libc.EBADF);
                return -1;
            }
            if (r == -LOOP_ENOTSOCK && !f.notSock) {
                f.notSock = true;
                continue;
            }
            if (r == 0 || r == -libc.EAGAIN || r == -libc.EINTR) {
                if (loopPark(t, fd, libc.POLLOUT, until, false, false) == 0) {
                    os.setErrno(libc.ETIMEDOUT);
                    return -1;
                }
                continue;
            }
            os.setErrno((i32)(-r));
            return -1;
        }
        return data.len;
    }
#endif
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

#ifdef IS_LINUX
/* io_uring: an ACCEPT on lfd completed, leaving clients queued or not;
 * a long enough queue makes the next accepts plain ones
 * (LOOP_ACCEPT_QUEUE) */
static void loopAcceptQueued(Loop *lp, i32 lfd, bool queued) {
    LoopFd *f = lp.fdw[lfd];
    if (!queued) {
        f.queuedRun = 0;
        return;
    }
    if (++f.queuedRun < LOOP_ACCEPT_RUN) { return; }
    f.queuedRun = 0;
    u32 info[64];        /* struct tcp_info; tcpi_unacked: a listener's queue */
    u32 len = sizeof(info);
    if (libc.getsockopt(lfd, libc.IPPROTO_TCP, LOOP_TCP_INFO, info, &len) == 0 && len > 28) {
        f.more = info[6] >= LOOP_ACCEPT_QUEUE;
    }
}
#endif

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
#ifdef IS_LINUX
        if (lp.backend == LOOP_BK_URING) {
            LoopFd *lf = loopFdOf(lp, lfd);
            if (lf.more) {
                /* a burst: take it with plain accepts until none is left
                 * (LOOP_ACCEPT_QUEUE) */
                fd = accept4(lfd, null, null, (i32)(LOOP_SOCK_NONBLOCK | LOOP_SOCK_CLOEXEC));
                if (fd >= 0) {
                    loopForget(lp, fd);
                    return fd;
                }
                lf.more = false;
                err = os.errno();
                if (err == libc.EAGAIN || err == libc.EWOULDBLOCK || err == libc.EINTR ||
                    err == LOOP_ECONNABORTED) {
                    continue;
                }
            } else {
                /* ACCEPT: the client as a non-blocking, close-on-exec fd,
                 * as net.accept gives it; coro.stop cancels it */
                r = loopOp(lp, t, LOOP_OP_ACCEPT, lfd, 0, 0, LOOP_SOCK_NONBLOCK | LOOP_SOCK_CLOEXEC, 0,
                           until, true);
                if (r >= 0) {
                    fd = (i32)r;
                    loopAcceptQueued(lp, lfd, (t.cqeFlags & LOOP_CQE_F_SOCK_NONEMPTY) != 0);
                    loopForget(lp, fd);
                    return fd;
                }
                if (r == -LOOP_ECANCELED) {
                    if (t.why == LOOP_WHY_CLOSE) { os.setErrno(libc.EBADF); }
                    return -1;
                }
                err = (i32)(-r);
            }
            os.setErrno(err);
            if (err == libc.EMFILE || err == libc.ENFILE || err == LOOP_ENOBUFS || err == libc.ENOMEM) {
                loopFdsOut(lp);
            } else if (err == libc.EAGAIN || err == libc.EWOULDBLOCK) {
                r = loopPark(t, lfd, libc.POLLIN, until, true, false);
                if (r <= 0) { return -1; }
            } else if (err != libc.EINTR && err != LOOP_ECONNABORTED) {
                return -1;
            }
            continue;
        }
#endif
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
    if (lp && lp.backend == LOOP_BK_URING) {
#ifdef IS_LINUX
        /* its operations hold the file: cancel them, and each waiter
         * resumes on its completion (EBADF, POLLNVAL) */
        if (fd >= 0 && fd < lp.fdwCap && lp.fdw[fd]) {
            LoopFd *f = lp.fdw[fd];
            for (i64 i = 0; i < f.n; i++) { loopCancel(lp, f.w[i], LOOP_WHY_CLOSE); }
            if (f.stream) { loopStreamClose(lp, f); }
        }
        loopForget(lp, fd);
#endif
    } else if (lp) {
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
        if (!t.waiting || t.ready || !t.idle) { continue; }
#ifdef IS_LINUX
        if (t.op) {
            loopCancel(lp, t, LOOP_WHY_STOP);   /* resumes on its completion */
            continue;
        }
#endif
        loopReady(lp, t, -1);
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
#ifdef IS_LINUX
    if (lp.backend == LOOP_BK_URING) {
        if (loopUringEnter(lp, timeout, true) < 0) {
            i32 err = os.errno();
            if (err != libc.EINTR && err != LOOP_ETIME && err != LOOP_EBUSY && err != libc.EAGAIN) {
                throw(ERR_LOOP, "coro.run: io_uring_enter failed (errno %d)", err);
            }
        }
        loopUringReap(lp);
        return;
    }
#endif
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
            if (!t.waiting || t.ready || t.deadline < 0 || t.cancelled) { continue; }
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
#ifdef IS_LINUX
                if (t.op) {
                    loopCancel(lp, t, LOOP_WHY_TIMEOUT);   /* resumes on its completion */
                    continue;
                }
#endif
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
