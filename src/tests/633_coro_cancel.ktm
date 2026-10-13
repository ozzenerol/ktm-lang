import coro, date, libc, mem, net, os, test;

/* Timeouts, coro.stop and coro.close while a coroutine waits, the same on
 * every backend of coro's loop (v0.13.0): on io_uring each of them
 * cancels an operation in flight, and the coroutine resumes only on its
 * completion. Each backend runs in a thread of its own (KTM_CORO_BACKEND
 * is read when a thread's loop is made); one that can't be had (io_uring
 * in Docker) runs on the next one down, which is tested the same way. */

extern "c" i32 socketpair(i32 domain, i32 type, i32 protocol, i32 *sv);

i64 gCorrect = 0, gTests = 0;
u8[] gBackend;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed on %s: %s\n", gBackend.ptr, what); }
}

void pair(i32 *sv) {
    socketpair(libc.AF_UNIX, libc.SOCK_STREAM, 0, sv);
    net.setNonBlocking(sv[0]);
    net.setNonBlocking(sv[1]);
}

/* ---- timeouts ---- */

i64 gReadR, gReadErr, gReadMs, gLateR, gWaitR, gAcceptR, gWriteR, gWriteErr, gSlept;
u8 gLate[8];

void *timeouts(void *unused) {
    i32 a[2];
    pair(a);
    u8 buf[16];

    /* coro.read gives up: -1, ETIMEDOUT, after about 100 ms */
    i64 t0 = date.monoMs();
    gReadR = coro.read(a[0], buf[0:16], 100);
    gReadErr = os.errno();
    gReadMs = date.monoMs() - t0;

    /* the timed-out read took nothing: what comes later is all there */
    coro.write(a[1], "late");
    gLateR = coro.read(a[0], gLate[0:8], 2000);

    /* a sleep after a cancelled operation keeps its deadline */
    t0 = date.monoMs();
    coro.sleep(30);
    gSlept = date.monoMs() - t0;

    /* coro.wait and coro.accept give up */
    gWaitR = coro.wait(a[0], libc.POLLIN, 50);
    i32 l = net.listen(0, "127.0.0.1", 4);
    gAcceptR = coro.accept(l, 50);
    libc.close(l);

    /* coro.write into a full socket nobody reads gives up */
    i64 big = 8 * 1024 * 1024;
    u8 *data = mem.allocZero(big);
    gWriteR = coro.write(a[0], data[0:big], 200);
    gWriteErr = os.errno();
    mem.free(data);
    coro.close(a[0]);
    coro.close(a[1]);
    return null;
}

/* ---- coro.stop ---- */

i64 gIdleR = -2, gStopAcceptR = -2, gPlainR = -2;
i32 gStopFd[2];
i32 gStopListen;

void *idleWaiter(void *unused) {
    gIdleR = coro.waitIdle(gStopFd[0], libc.POLLIN);
    return null;
}

void *stopAccepter(void *unused) {
    gStopAcceptR = coro.accept(gStopListen);
    return null;
}

void *plainWaiter(void *unused) {
    /* a plain wait is work in flight: coro.stop doesn't end it */
    gPlainR = coro.wait(gStopFd[0], libc.POLLIN, 150);
    return null;
}

void *stopper(void *unused) {
    coro.sleep(30);
    coro.stop();
    return null;
}

/* ---- coro.close of an fd others wait on ---- */

i64 gClosedRead = -2, gClosedErr = 0, gClosedWait = -2, gCloseMs = -1;
i32 gClosing[2];

void *closedReader(void *unused) {
    u8 buf[16];
    i64 t0 = date.monoMs();
    gClosedRead = coro.read(gClosing[0], buf[0:16]);
    gClosedErr = os.errno();
    gCloseMs = date.monoMs() - t0;
    return null;
}

void *closedWaiter(void *unused) {
    gClosedWait = coro.wait(gClosing[0], libc.POLLIN);
    return null;
}

void *closer(void *unused) {
    coro.sleep(30);
    coro.close(gClosing[0]);
    return null;
}

void *scenarios(void *unused) {
    gBackend = coro.backend();
    coro.go(&timeouts);
    coro.run();

    pair(gStopFd);
    gStopListen = net.listen(0, "127.0.0.1", 4);
    coro.go(&idleWaiter);
    coro.go(&stopAccepter);
    coro.go(&plainWaiter);
    coro.go(&stopper);
    i64 t0 = date.monoMs();
    coro.run();
    gIdleR = gIdleR * 1000 + (date.monoMs() - t0 < 2000);   /* -1 and quick: -999 */
    libc.close(gStopFd[0]);
    libc.close(gStopFd[1]);
    libc.close(gStopListen);

    pair(gClosing);
    coro.go(&closedReader);
    coro.go(&closedWaiter);
    coro.go(&closer);
    coro.run();
    libc.close(gClosing[1]);
    return null;
}

void on(u8 *backend) {
    libc.setenv("KTM_CORO_BACKEND", backend, 1);
    libc.pthread_t *th;
    libc.pthread_create(&th, null, &scenarios, null);
    libc.pthread_join(th);

    check(gReadR == -1 && gReadErr == libc.ETIMEDOUT, "coro.read times out with -1, ETIMEDOUT");
    check(gReadMs >= 90 && gReadMs < 1500, "after about its time");
    check(gLateR == 4 && !libc.memcmp(gLate, "late", 4), "a timed-out read took nothing");
    check(gSlept >= 25 && gSlept < 1500, "a sleep after a cancelled operation keeps its time");
    check(gWaitR == 0, "coro.wait times out with 0");
    check(gAcceptR == -1, "coro.accept times out with -1");
    check(gWriteR == -1 && gWriteErr == libc.ETIMEDOUT, "coro.write into a full socket times out");
    check(gIdleR == -999, "coro.stop ends a waitIdle with -1, at once");
    check(gStopAcceptR == -1, "and a coro.accept");
    check(gPlainR == 0, "a plain wait goes on to its timeout");
    check(gClosedRead == -1 && gClosedErr == libc.EBADF, "coro.close: a read on it gives -1, EBADF");
    check(gCloseMs >= 0 && gCloseMs < 1500, "at once");
    check((gClosedWait & libc.POLLNVAL) != 0, "and a wait on it POLLNVAL");
}

i32 main() {
    printf("Test - coro cancel and timeouts: ");
#ifdef IS_MACOS
    on("kqueue");
#else
    on("uring");
    on("epoll");
#endif
    on("poll");
    libc.setenv("KTM_CORO_BACKEND", "", 1);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
