module coro;
import atomic, cstr, libc, mem, os, proc, thread;

/* CPU-heavy work off the loop (v0.20.0): password hashing, compression,
 * image work. A loop runs one coroutine at a time and only switches when
 * one waits, so 20 ms of computing would hold every other coroutine of
 * its loop for 20 ms; coro.offload runs it on a worker thread instead
 * and the coroutine waits for it as it waits for I/O. */

struct OffloadJob {
    fn(void *arg) void body;
    void *arg;
    i32 w;              /* the pipe the worker writes a byte to when done */
    i64 code;           /* what body threw, if it did */
    u8 *message;
};

static thread.Pool *offloadPool;
static i64 offloadState;    /* 0: no pool yet, 1: starting, 2: ready, 3: none (inline) */

static void offloadRun(void *priv, void *argv) {
    OffloadJob *j = argv;
    try {
        j.body(j.arg);
    } catch (Error e) {
        j.code = e.code;
        j.message = cstr.dup(e.message);
    }
    u8 one = 1;
    libc.write(j.w, &one, 1);   /* the last touch: the coroutine frees j after */
}

/* The pool, started on first use: as many workers as cores, at least 2 */
static thread.Pool *offloadGetPool() {
    while (true) {
        i64 s = atomic.load(&offloadState);
        if (s == 2) { return offloadPool; }
        if (s == 3) { return null; }
        if (s == 0 && atomic.cas(&offloadState, 0, 1)) {
            i64 n = proc.cpuCount();
            offloadPool = thread.poolNew(n < 2 ? 2 : n);
            atomic.store(&offloadState, offloadPool ? 2 : 3);
            return offloadPool;
        }
    }
}

/* body(arg) on a worker thread while this coroutine waits (its loop keeps
 * running the others); outside a loop coroutine, or without threads,
 * body(arg) runs here. Returns when body has returned; what body throws
 * is thrown here. body must not touch the loop (coro.read, coro.park...):
 * it runs on another thread, with arg as its only link to the caller. */
pub void offload(fn(void *arg) void body, void *arg = null) {
    thread.Pool *pool = inLoop() ? offloadGetPool() : null;
    i32 fds[2];
    if (!pool || pipe(fds) != 0) {
        body(arg);
        return;
    }
    libc.fcntl(fds[0], libc.F_SETFD, libc.FD_CLOEXEC);
    libc.fcntl(fds[1], libc.F_SETFD, libc.FD_CLOEXEC);
    OffloadJob *j = mem.allocZero(sizeof(OffloadJob));
    j.body = body;
    j.arg = arg;
    j.w = fds[1];
    thread.poolEnqueue(pool, j, &offloadRun);
    u8 b;
    while (true) {
        wait(fds[0], libc.POLLIN);
        i64 n = libc.read(fds[0], &b, 1);
        if (n == 1) { break; }
        if (n < 0 && os.errno() != libc.EINTR && os.errno() != libc.EAGAIN) { break; }
    }
    close(fds[0]);      /* coro.close: the loop forgets the fd (its number comes back) */
    libc.close(fds[1]);
    i64 code = j.code;
    u8 *msg = j.message;
    mem.free(j);
    if (msg) {
        defer mem.free(msg);
        throw(code, "%s", msg);
    }
}
