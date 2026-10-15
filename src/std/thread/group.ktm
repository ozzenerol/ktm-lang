module thread;
import cstr, libc, mem, proc, rt;

/* Waits for a group of threads: add(n) before starting them (go() does it
 * for you), done() as each ends, wait() until all have. Works as declared,
 * with no init and nothing to free; don't copy one that is in use.
 *     thread.WaitGroup wg;
 *     for (i64 i = 0; i < 4; i++) { thread.go(&work, &parts[i], &wg); }
 *     wg.wait();
 * wait() throws the first error a go() thread threw. */
pub struct WaitGroup {
    Mutex m;
    Cond c;
    i64 count;
    bool failed;
    Error err;      /* the first error, its message a copy */
};

/* Adds n threads (or takes them away, n < 0) to wait for. Throws
 * ERR_THREAD when the count would go below 0. */
pub void WaitGroup.add(i64 n = 1) {
    self.m.lock();
    if (self.count + n < 0) {
        self.m.unlock();
        throw(ERR_THREAD, "thread.WaitGroup: done() called more often than add()");
    }
    self.count += n;
    if (self.count == 0) {
        self.c.broadcast();
    }
    self.m.unlock();
}

/* One thread of the group has ended */
pub void WaitGroup.done() {
    self.add(-1);
}

/* Waits until every thread of the group has ended (count 0). Throws the
 * first error a go() thread threw since the last wait(), with its code,
 * message, file and line; the group can then be used again. */
pub void WaitGroup.wait() {
    self.m.lock();
    while (self.count > 0) {
        self.c.wait(&self.m);
    }
    bool failed = self.failed;
    Error e = self.err;
    self.failed = false;
    self.m.unlock();
    if (failed) {
        defer mem.free(e.message);
        throw(e);
    }
}

/* Keeps e for wait() when it is the group's first error */
static void groupFail(WaitGroup *wg, Error e) {
    wg.m.lock();
    if (!wg.failed) {
        wg.failed = true;
        wg.err = e;
        wg.err.message = cstr.dup(e.message);
    }
    wg.m.unlock();
}

/* A go() thread: what it runs, and the group it reports to */
struct GoRun {
    fn(void *) void body;
    fn() void plain;
    void *arg;
    WaitGroup *wg;
};

static void *goStart(void *p) {
    GoRun *r = p;
    rt.__ktmCrashThread();
    WaitGroup *wg = r.wg;
    try {
        if (r.body) {
            r.body(r.arg);
        } else {
            r.plain();
        }
    } catch (Error e) {
        groupFail(wg, e);
    }
    mem.free(r);
    wg.done();
    return null;
}

static void goNew(fn(void *) void body, fn() void plain, void *arg, WaitGroup *wg) {
    GoRun *r = mem.allocZero(sizeof(GoRun));
    r.body = body;
    r.plain = plain;
    r.arg = arg;
    r.wg = wg;
    wg.add(1);
    /* the thread frees r, maybe before pthread_create returns: its
     * handle goes into a local */
    libc.pthread_t *th;
    i32 err = libc.pthread_create(&th, null, &goStart, r);
    if (err != 0) {
        mem.free(r);
        wg.done();
        throw(ERR_THREAD, "thread.go: can't start a thread: %s", libc.strerror(err));
    }
    libc.pthread_detach(th);
}

/* Runs f(arg) on a new thread that wg waits for: go() adds 1 to wg and
 * the thread calls wg.done() when f returns. There is nothing to join:
 * what f throws, wg.wait() throws. Keep arg alive until wg.wait()
 * returns. Throws ERR_THREAD when no thread can be started. */
pub void go<T>(fn(T *) void f, T *arg, WaitGroup *wg) {
    goNew((fn(void *) void)f, null, (void *)arg, wg);
}

/* Runs f() on a new thread that wg waits for */
pub void go(fn() void f, WaitGroup *wg) {
    goNew(null, f, null, wg);
}

/* One thread's share of each(): items [from, to) */
struct EachPart {
    u8 *base;
    i64 size;
    i64 from;
    i64 to;
    fn(void *) void f;
};

static void eachRun(EachPart *p) {
    for (i64 i = p.from; i < p.to; i++) {
        p.f(p.base + i * p.size);
    }
}

/* Calls f(&items[i]) for every item, the items split over as many
 * threads as the machine has cores (one per item for fewer items), and
 * returns when all are done. Throws the first error f threw. f gets each
 * item once, from one thread; items that f changes are shared with
 * nothing else, but anything else f touches needs a lock. */
pub void each<T>(T[] items, fn(T *) void f) {
    i64 n = items.len;
    if (n == 0) { return; }
    i64 k = proc.cpuCount();
    if (k < 1) { k = 1; }
    if (k > n) { k = n; }
    EachPart *parts = mem.allocZero(sizeof(EachPart) * k);
    defer mem.free(parts);
    WaitGroup wg;
    for (i64 j = 0; j < k; j++) {
        EachPart *p = &parts[j];
        p.base = (u8 *)items.ptr;
        p.size = sizeof(T);
        p.from = n * j / k;
        p.to = n * (j + 1) / k;
        p.f = (fn(void *) void)f;
        try {
            go(&eachRun, p, &wg);
        } catch (Error e) {
            /* no thread to be had: this part runs here */
            try {
                eachRun(p);
            } catch (Error e2) {
                groupFail(&wg, e2);
            }
        }
    }
    wg.wait();
}
