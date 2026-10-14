import atomic, coro, test;

/* coro.unpark from another thread (v0.20.0): through the target loop's
 * mailbox. A park with no time limit woken by a plain thread; an unpark
 * that comes before the park; two loops on two threads handing a token
 * back and forth 20000 times. */

extern "c" i32 pthread_create(void *t, void *attr, void *start, void *arg);
extern "c" i32 pthread_join(void *t, void **ret);
extern "c" i32 usleep(u32 us);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

/* 1. a park with no limit, woken by a plain thread */
i64 gGot = -9, gReady = 0;
coro.Coroutine *gSleeper;

void *sleeper(void *arg) {
    atomic.store(&gReady, 1);
    gGot = coro.park();
    return null;
}

void *waker(void *arg) {
    while (!atomic.load(&gReady)) { usleep(1000); }
    usleep(20000);                      /* it is parked by now */
    coro.unpark(gSleeper);
    return null;
}

/* 2. the unpark first */
i64 gEarly = -9, gSent = 0;
coro.Coroutine *gLate;

void *late(void *arg) {
    while (!atomic.load(&gSent)) { coro.sleep(1); }
    gEarly = coro.park(5000);
    return null;
}

void *earlyWaker(void *arg) {
    while (!atomic.load(&gReady)) { usleep(1000); }
    coro.unpark(gLate);
    atomic.store(&gSent, 1);
    return null;
}

/* 3. ping-pong between two loops */
coro.Coroutine *gA, *gB;
i64 gTurns = 0, gRounds = 20000, gBad = 0, gUp = 0;

void *playerA(void *arg) {
    atomic.add(&gUp, 1);
    while (atomic.load(&gUp) < 2) { coro.sleep(1); }
    for (i64 i = 0; i < gRounds; i++) {
        coro.unpark(gB);
        if (coro.park(5000) != 1) { atomic.add(&gBad, 1); return null; }
        atomic.add(&gTurns, 1);
    }
    coro.unpark(gB);
    return null;
}

void *playerB(void *arg) {
    atomic.add(&gUp, 1);
    for (i64 i = 0; i <= gRounds; i++) {
        if (coro.park(5000) != 1) { atomic.add(&gBad, 1); return null; }
        if (i < gRounds) { coro.unpark(gA); }
    }
    return null;
}

void *loopB(void *arg) {
    gB = coro.go(&playerB);
    atomic.store(&gReady, 2);
    coro.run();
    return null;
}

i32 main() {
    printf("Test - coro.unpark from another thread: ");
    void *t;
    gSleeper = coro.go(&sleeper);
    pthread_create(&t, null, &waker, null);
    coro.run();
    pthread_join(t, null);
    check(gGot == 1, "a plain thread wakes a park with no time limit");

    gReady = 0;
    gLate = coro.go(&late);
    gReady = 1;
    pthread_create(&t, null, &earlyWaker, null);
    coro.run();
    pthread_join(t, null);
    check(gEarly == 1, "an unpark from another thread before the park counts");

    gReady = 0;
    pthread_create(&t, null, &loopB, null);
    while (atomic.load(&gReady) != 2) { usleep(1000); }
    gA = coro.go(&playerA);
    coro.run();
    pthread_join(t, null);
    check(gTurns == gRounds && gBad == 0, "20000 hand-offs between two loops");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
