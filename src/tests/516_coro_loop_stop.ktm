import coro, libc, signal, test;

/* coro.stop (v0.5.0): waitIdle returns -1 when stop() is called during
 * it and at once after, plain waits and sleeps go on, run() returns once
 * they end, and the next loop starts out not stopping. A stop signal
 * fits as a coroutine waiting on signal.stopInit()'s fd. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 gIdle[2], gBusy[2];
i64 gIdleRet = -2, gBusyRet = -2, gAfterRet = -2, gSlept = 0, gSigRet = -2;
bool gSawStopping = false;

void *idler(void *arg) {
    gIdleRet = coro.waitIdle(gIdle[0], libc.POLLIN);
    gAfterRet = coro.waitIdle(gIdle[0], libc.POLLIN, 1000);
    return null;
}

/* in flight: a plain wait the stop doesn't end */
void *busy(void *arg) {
    gBusyRet = coro.wait(gBusy[0], libc.POLLIN, 1000);
    return null;
}

void *slow(void *arg) {
    coro.sleep(60);
    gSlept = 1;
    return null;
}

void *stopper(void *arg) {
    coro.sleep(20);
    coro.stop();
    gSawStopping = coro.stopping();
    coro.sleep(20);
    libc.write(gBusy[1], "b", 1);
    return null;
}

void *idleOnce(void *arg) {
    gIdleRet = coro.waitIdle(gIdle[0], libc.POLLIN, (i64)arg);
    return null;
}

/* the stop signal as a coroutine */
void *onSignal(void *arg) {
    i32 fd = (i32)(i64)arg;
    gSigRet = coro.waitIdle(fd, libc.POLLIN);
    if (gSigRet > 0) { coro.stop(); }
    return null;
}

void *raiser(void *arg) {
    coro.sleep(20);
    libc.kill(libc.getpid(), libc.SIGTERM);
    return null;
}

i32 main() {
    printf("Test - coro loop stop: ");
    libc.pipe(gIdle);
    libc.pipe(gBusy);

    coro.go(&idler);
    coro.go(&busy);
    coro.go(&slow);
    coro.go(&stopper);
    coro.run();
    check(gIdleRet == -1, "stop() ends a waitIdle with -1");
    check(gAfterRet == -1, "waitIdle after stop() returns -1 at once");
    check(gSawStopping, "stopping() is true after stop()");
    check(gBusyRet > 0, "a plain wait goes on after stop() and sees its fd");
    check(gSlept == 1, "a sleep goes on after stop()");
    check(!coro.stopping(), "run() returned: the next loop isn't stopping");

    gIdleRet = -2;
    coro.stop();
    coro.go(&idleOnce, (void *)1000);
    coro.run();
    check(gIdleRet == -1, "stop() before run(): waitIdle returns -1 at once");

    gIdleRet = -2;
    coro.go(&idleOnce, (void *)10);
    coro.run();
    check(gIdleRet == 0, "after that run(), waitIdle times out with 0 again");

    i32 sfd = signal.stopInit();
    check(sfd >= 0, "signal.stopInit");
    gIdleRet = -2;
    coro.go(&onSignal, (void *)(i64)sfd);
    coro.go(&idleOnce, (void *)-1);
    coro.go(&raiser);
    coro.run();
    check(gSigRet > 0, "the signal coroutine woke on the stop fd");
    check(gIdleRet == -1, "and its coro.stop() ended the idle wait");
    check(signal.stopRequested(), "signal.stopRequested()");

    libc.close(gIdle[0]);
    libc.close(gIdle[1]);
    libc.close(gBusy[0]);
    libc.close(gBusy[1]);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
