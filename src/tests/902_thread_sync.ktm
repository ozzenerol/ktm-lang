import atomic, cstr, mem, test, thread;

/* v0.24.0: waiting for groups of threads and passing values between them.
 * thread.go(&f, &arg, &wg) runs f on a thread wg waits for (wg.wait()
 * throws what one threw); a Channel<T> queues values between threads
 * (cap 0: unbuffered, send() returns once the value is taken); each()
 * calls f on every item of a slice from several threads; thread.pool(n)
 * runs functions on n threads, Pool.wait() throwing what one threw. The
 * old pool functions keep working (13_threads, 331, 339). */

extern "c" i32 usleep(u32 usec);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

enum Err { bad = 7 };

struct Part { i64 from, to, sum; };

void add(Part *p) {
    for (i64 i = p.from; i < p.to; i++) { p.sum += i; }
}

i64 gTicks;
void tick() { atomic.add(&gTicks, 1); }

void fails(Part *p) {
    throw(Err.bad, "part %d failed", p.from);
}

/* the error a call threw: its code, and whether its message is msg */
i64 thrownCode(Error e, u8 *msg) {
    if (msg && cstr.cmp(e.message, msg)) { return -1; }
    return e.code;
}

/* Channel: two consumers add what one producer sends */
thread.Channel<i64> gCh;
thread.Mutex gSumLock;
i64 gSum, gGot;

void consumeAll() {
    i64 v;
    while (gCh.recv(&v)) {
        gSumLock.lock();
        gSum += v;
        gGot++;
        gSumLock.unlock();
    }
}

struct Point { i64 x, y; };
thread.Channel<Point> gPts;

void sendPoints() {
    for (i64 i = 1; i <= 3; i++) {
        Point p = {i, i * 10};
        gPts.send(p);
    }
    gPts.close();
}

/* unbuffered: the receiver comes late */
thread.Channel<i64> gUn;
void lateRecv() {
    usleep(30000);
    i64 v;
    gUn.recv(&v);
}

void twice(i64 *p) { *p *= 2; }

void failOn500(i64 *p) {
    if (*p == 500) { throw(Err.bad, "item 500"); }
}

i32 main() {
    printf("Test - thread sync: ");

    /* WaitGroup + go */
    Part parts[8];
    thread.WaitGroup wg;
    for (i64 i = 0; i < 8; i++) {
        parts[i].from = i * 1000;
        parts[i].to = (i + 1) * 1000;
        parts[i].sum = 0;
        thread.go(&add, &parts[i], &wg);
    }
    for (i64 i = 0; i < 5; i++) { thread.go(&tick, &wg); }
    wg.wait();
    i64 total = 0;
    for (i64 i = 0; i < 8; i++) { total += parts[i].sum; }
    check(total == 31996000 && gTicks == 5, "go() threads, waited for by a WaitGroup");

    i64 code = 0;
    thread.go(&fails, &parts[2], &wg);
    thread.go(&add, &parts[3], &wg);
    try {
        wg.wait();
    } catch (Error e) {
        code = thrownCode(e, "part 2000 failed");
    }
    check(code == Err.bad, "wait() throws what a go() thread threw");
    thread.go(&tick, &wg);
    wg.wait();
    check(gTicks == 6, "the WaitGroup works again after the error");

    code = 0;
    thread.WaitGroup manual;
    manual.add(2);
    manual.done();
    manual.done();
    manual.wait();
    try {
        manual.done();
    } catch (Error e) {
        code = e.code;
    }
    check(code == thread.ERR_THREAD, "done() more often than add() throws ERR_THREAD");

    /* a buffered Channel */
    gCh = thread.channel<i64>(8);
    thread.WaitGroup cw;
    thread.go(&consumeAll, &cw);
    thread.go(&consumeAll, &cw);
    for (i64 v = 1; v <= 1000; v++) { gCh.send(v); }
    gCh.close();
    cw.wait();
    check(gSum == 500500 && gGot == 1000, "a Channel's values reach the receivers once each");
    i64 v = 42;
    check(!gCh.recv(&v) && v == 42, "recv() on a closed, empty Channel is false");
    code = 0;
    try {
        gCh.send(1);
    } catch (Error e) {
        code = e.code;
    }
    check(code == thread.ERR_THREAD, "send() on a closed Channel throws ERR_THREAD");
    gCh.free();

    /* a Channel of structs, scoped */
    {
        gPts = thread.channel<Point>(1);
        thread.Thread ts = thread.spawn(&sendPoints);
        Point p;
        i64 xs = 0, ys = 0;
        while (gPts.recv(&p)) {
            xs += p.x;
            ys += p.y;
        }
        ts.join();
        check(xs == 6 && ys == 60, "a Channel of structs");
        gPts.free();
    }

    /* unbuffered: send() returns once the value is taken */
    gUn = thread.channel<i64>(0);
    thread.Thread tu = thread.spawn(&lateRecv);
    gUn.send(5);
    check(gUn.count() == 0, "an unbuffered send() returns once a recv() took the value");
    tu.join();
    gUn.free();
    {
        scoped thread.Channel<i64> b = thread.channel<i64>(4);
        b.send(1);
        b.send(2);
        check(b.count() == 2, "a buffered send() returns at once");
    }

    /* each */
    i64 n = 10000;
    i64 *nums = mem.alloc(sizeof(i64) * n);
    for (i64 i = 0; i < n; i++) { nums[i] = i; }
    thread.each(nums[0:n], &twice);
    bool ok = true;
    for (i64 i = 0; i < n; i++) { if (nums[i] != i * 2) { ok = false; } }
    check(ok, "each() calls f on every item once");
    i64 three[3] = {1, 2, 3};
    thread.each(three[0:3], &twice);
    check(three[0] == 2 && three[1] == 4 && three[2] == 6, "each() on a short slice");
    thread.each(three[0:0], &twice);
    check(three[0] == 2, "each() on an empty slice does nothing");
    for (i64 i = 0; i < n; i++) { nums[i] = i; }
    code = 0;
    try {
        thread.each(nums[0:n], &failOn500);
    } catch (Error e) {
        code = thrownCode(e, "item 500");
    }
    check(code == Err.bad, "each() throws what f threw");
    mem.free(nums);

    /* Pool */
    gTicks = 0;
    thread.Pool *p = thread.pool(4);
    check(p.workerCount == 4, "thread.pool(4) starts 4 threads");
    Part pp[100];
    for (i64 i = 0; i < 100; i++) {
        pp[i].from = 0;
        pp[i].to = i + 1;
        pp[i].sum = 0;
        p.run(&add, &pp[i]);
    }
    p.wait();
    ok = true;
    for (i64 i = 0; i < 100; i++) { if (pp[i].sum != i * (i + 1) / 2) { ok = false; } }
    check(ok, "Pool.run runs every function, Pool.wait waits for them");
    code = 0;
    p.run(&fails, &pp[9]);
    p.run(&add, &pp[10]);
    try {
        p.wait();
    } catch (Error e) {
        code = thrownCode(e, "part 0 failed");
    }
    check(code == Err.bad, "Pool.wait throws what a function threw");
    p.run(&add, &pp[11]);
    p.wait();
    check(true, "the next Pool.wait has nothing to throw");
    p.free();
    {
        scoped thread.Pool *q = thread.pool();
        check(q.workerCount >= 1, "thread.pool() starts one thread per core");
        q.run(&add, &pp[12]);
        q.wait();
    }

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
