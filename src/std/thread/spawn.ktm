module thread;
import cstr, libc, mem, rt;

/* The code of the Error thread.spawn throws when no thread can be started */
pub const i64 ERR_THREAD = 0x6B746D40;

/* What a started thread needs, and what it leaves for join() */
struct ThreadRun {
    libc.pthread_t *th;
    fn(void *) void body;   /* spawn(f, arg) */
    fn() void plain;        /* spawn(f) */
    void *arg;
    bool failed;
    Error err;              /* what the thread threw, its message a copy */
};

/* A started thread: join() waits for it. A Thread is joined once; a zero
 * Thread, or one already joined, joins as nothing. */
pub struct Thread {
    ThreadRun *run;
};

/* Every thread spawn starts runs here: ktm's runtime is set up for it
 * (crash reports, try, threadlocal), and what the function throws is kept
 * for join() */
static void *threadStart(void *p) {
    ThreadRun *r = p;
    rt.__ktmCrashThread();
    try {
        if (r.body) {
            r.body(r.arg);
        } else {
            r.plain();
        }
    } catch (Error e) {
        r.failed = true;
        r.err = e;
        r.err.message = cstr.dup(e.message);
    }
    return null;
}

static Thread threadNew(fn(void *) void body, fn() void plain, void *arg) {
    ThreadRun *r = mem.allocZero(sizeof(ThreadRun));
    r.body = body;
    r.plain = plain;
    r.arg = arg;
    i32 err = libc.pthread_create(&r.th, null, &threadStart, r);
    if (err != 0) {
        mem.free(r);
        throw(ERR_THREAD, "thread.spawn: can't start a thread: %s", libc.strerror(err));
    }
    Thread t;
    t.run = r;
    return t;
}

/* Runs f(arg) on a new thread. arg is shared with the caller: keep it
 * alive, and don't change it from both sides without a Mutex, until
 * join() returns. Throws ERR_THREAD when no thread can be started. */
pub Thread spawn<T>(fn(T *) void f, T *arg) {
    return threadNew((fn(void *) void)f, null, (void *)arg);
}

/* Runs f() on a new thread */
pub Thread spawn(fn() void f) {
    return threadNew(null, f, null);
}

/* Waits for the thread to end. What the thread threw is thrown here, with
 * its code, message, file and line. */
pub void Thread.join() {
    ThreadRun *r = self.run;
    if (!r) { return; }
    self.run = null;
    libc.pthread_join(r.th);
    bool failed = r.failed;
    Error e = r.err;
    mem.free(r);
    if (failed) {
        defer mem.free(e.message);
        throw(e);
    }
}
