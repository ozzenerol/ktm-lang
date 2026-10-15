import cstr, fs, libc, mem, proc, test, thread;

/* v0.24.0: the thread module starts threads and locks without C.
 * thread.spawn(&f, &arg) runs f(T *) on a new thread (spawn(&f) a
 * function with no argument); join() waits and throws what the thread
 * threw. Mutex (with guard() for scoped), Cond, RwLock and Once work as
 * declared, with no init. A spawned thread has ktm's runtime: a crash in
 * it is reported with its frames, and threadlocal globals are its own. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

struct Part { i64 from, to, sum; };

void add(Part *p) {
    for (i64 i = p.from; i < p.to; i++) { p.sum += i; }
}

/* a Mutex: four threads add 50000 each */
i64 gHits;
thread.Mutex gLock;

void hitLocked() {
    for (i64 i = 0; i < 50000; i++) {
        gLock.lock();
        gHits++;
        gLock.unlock();
    }
}

void hitGuarded() {
    for (i64 i = 0; i < 50000; i++) {
        scoped thread.Guard g = gLock.guard();
        gHits++;
    }
}

/* a thread that throws */
enum Err { bad = 7 };

void fails(Part *p) {
    throw(Err.bad, "part %d failed", p.from);
}

/* Cond: a consumer waits for a producer */
thread.Mutex gQm;
thread.Cond gQc;
i64 gQueued, gTaken;

void consume() {
    for (i64 n = 0; n < 1000; n++) {
        gQm.lock();
        while (gQueued == 0) { gQc.wait(&gQm); }
        gQueued--;
        gTaken++;
        gQm.unlock();
    }
}

/* RwLock: readers check a pair a writer keeps equal */
thread.RwLock gRw;
i64 gA, gB, gTorn;

void writer() {
    for (i64 i = 0; i < 20000; i++) {
        gRw.writeLock();
        gA++;
        gB++;
        gRw.writeUnlock();
    }
}

void reader() {
    for (i64 i = 0; i < 20000; i++) {
        gRw.readLock();
        if (gA != gB) { gTorn++; }
        gRw.readUnlock();
    }
}

/* Once: run once by whichever thread is first */
thread.Once gOnce;
i64 gInits;

void initOnce() { gInits++; }
void useOnce() { gOnce.run(&initOnce); }

/* threadlocal: each thread its own */
threadlocal i64 tlCount;
i64 gTlSeen[3];

void countTl(i64 *slot) {
    for (i64 i = 0; i < 100; i++) { tlCount++; }
    *slot = tlCount;
}

#define CR_SRC "./ktm-900-crash.ktm"
#define CR_ERR "./ktm-900-crash.err"

i32 main() {
    printf("Test - thread spawn and locks: ");

    Part a = {1, 501, 0}, b = {501, 1001, 0};
    thread.Thread ta = thread.spawn(&add, &a);
    thread.Thread tb = thread.spawn(&add, &b);
    ta.join();
    tb.join();
    check(a.sum + b.sum == 500500, "spawn with an argument, join");

    thread.Thread t[4];
    for (i64 i = 0; i < 4; i++) { t[i] = thread.spawn(i % 2 ? &hitGuarded : &hitLocked); }
    for (i64 i = 0; i < 4; i++) { t[i].join(); }
    check(gHits == 200000, "Mutex lock/unlock and guard()");

    check(gLock.tryLock(), "tryLock on a free Mutex");
    check(!gLock.tryLock(), "tryLock on a held Mutex");
    gLock.unlock();

    thread.Thread tf = thread.spawn(&fails, &a);
    i64 code = 0, line = 0;
    u8 *msg = null;
    try {
        tf.join();
    } catch (Error e) {
        code = e.code;
        line = e.line;
        msg = cstr.dup(e.message);
    }
    check(code == Err.bad && line == 45 && msg && !cstr.cmp(msg, "part 1 failed"),
          "join throws what the thread threw, with its line");
    mem.free(msg);
    tf.join();
    thread.Thread zero;
    zero.join();
    check(true, "a joined or zero Thread joins as nothing");

    thread.Thread tc = thread.spawn(&consume);
    for (i64 n = 0; n < 1000; n++) {
        gQm.lock();
        gQueued++;
        gQc.signal();
        gQm.unlock();
    }
    tc.join();
    check(gTaken == 1000 && gQueued == 0, "Cond wait and signal");

    thread.Thread tw = thread.spawn(&writer);
    thread.Thread tr[3];
    for (i64 i = 0; i < 3; i++) { tr[i] = thread.spawn(&reader); }
    tw.join();
    for (i64 i = 0; i < 3; i++) { tr[i].join(); }
    check(gTorn == 0 && gA == 20000, "RwLock keeps readers from a half write");

    thread.Thread to[4];
    for (i64 i = 0; i < 4; i++) { to[i] = thread.spawn(&useOnce); }
    for (i64 i = 0; i < 4; i++) { to[i].join(); }
    gOnce.run(&initOnce);
    check(gInits == 1, "Once runs once");

    thread.Thread tt[3];
    for (i64 i = 0; i < 3; i++) { tt[i] = thread.spawn(&countTl, &gTlSeen[i]); }
    for (i64 i = 0; i < 3; i++) { tt[i].join(); }
    check(gTlSeen[0] == 100 && gTlSeen[1] == 100 && gTlSeen[2] == 100 && tlCount == 0,
          "threadlocal is each thread's own");

    /* a crash in a spawned thread is reported with its ktm frames */
    fs.writeAll(CR_SRC, "import thread;\n"
                        "void boom(i64 *p) {\n"
                        "    *p = 1;\n"
                        "}\n"
                        "void main() {\n"
                        "    thread.Thread t = thread.spawn<i64>(&boom, null);\n"
                        "    t.join();\n"
                        "}\n", libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    for (i64 k = 0; k < 2; k++) {
        u8 *cmd = cstr.format("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} %s "
                              CR_SRC " > /dev/null 2> " CR_ERR, k ? "-jit" : "-run");
        proc.shell(cstr.view(cmd));
        mem.free(cmd);
        u8 *err = fs.readAll(CR_ERR).take();
        check(err && libc.strstr(err, "null pointer dereference") &&
              libc.strstr(err, "ktm-900-crash.ktm:3 in boom"),
              k ? "a crash in a thread names its frame (JIT)" : "a crash in a thread names its frame");
        mem.free(err);
    }
    proc.shell("rm -f " CR_SRC " " CR_ERR);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
