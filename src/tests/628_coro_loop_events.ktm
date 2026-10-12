import coro, date, fs, libc, net, os, str, test;

extern "c" i32 socketpair(i32 domain, i32 type, i32 protocol, i32 *sv);

/* coro's loop on the kernel's event queue (v0.11.0: epoll on Linux,
 * kqueue on macOS): many fds at once, a reader and a writer waiting on
 * one fd, an fd that fires while its waiter already timed out, a regular
 * file (always ready), coro.close of an fd still waited on, and the
 * echo of 200 connections through one loop. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s (errno %d)\n", what, os.errno()); }
}

const i64 NPAIRS = 200;
i32 gA[NPAIRS];
i32 gB[NPAIRS];
i64 gEchoed = 0;

/* one end of a pair: echo what comes, until the other end closes */
void *echoer(void *arg) {
    i32 fd = gB[(i64)arg];
    u8 buf[64];
    while (coro.wait(fd, libc.POLLIN, 5000) > 0) {
        i64 n = libc.recv(fd, buf, 64, 0);
        if (n <= 0) { break; }
        libc.send(fd, buf, n, 0);
    }
    return null;
}

/* the other end: send, wait for the echo, ten times */
void *pinger(void *arg) {
    i64 i = (i64)arg;
    i32 fd = gA[i];
    u8 buf[8];
    for (i64 k = 0; k < 10; k++) {
        libc.send(fd, "ping", 4, 0);
        if (coro.wait(fd, libc.POLLIN, 5000) <= 0) { return null; }
        if (libc.recv(fd, buf, 8, 0) == 4) { gEchoed++; }
    }
    libc.shutdown(fd, libc.SHUT_WR);
    return null;
}

i32 gPair[2];
i64 gReadGot = -2, gWriteGot = -2;
i64 gLog = 0;

/* a reader and a writer on one fd: the writer is answered at once (the
 * socket has room), the reader only when data comes */
void *reader(void *unused) {
    gReadGot = coro.wait(gPair[0], libc.POLLIN, 3000);
    gLog = gLog * 10 + 2;
    return null;
}

void *writer(void *unused) {
    gWriteGot = coro.wait(gPair[0], libc.POLLOUT, 3000);
    gLog = gLog * 10 + 1;
    coro.sleep(50);
    libc.send(gPair[1], "x", 1, 0);
    return null;
}

i64 gTimedOut = -2, gAfter = -2;

/* a wait that times out, then its fd fires with nobody waiting: the
 * next wait on it is answered */
void *lateFire(void *unused) {
    gTimedOut = coro.wait(gPair[0], libc.POLLIN, 30);
    libc.send(gPair[1], "y", 1, 0);
    coro.sleep(20);
    u8 c;
    libc.recv(gPair[0], &c, 1, 0);
    coro.sleep(20);
    libc.send(gPair[1], "z", 1, 0);
    gAfter = coro.wait(gPair[0], libc.POLLIN, 3000);
    return null;
}

i64 gFileGot = -2;
i32 gFileFd = -1;

void *fileWait(void *unused) {
    gFileGot = coro.wait(gFileFd, libc.POLLIN, 3000);
    return null;
}

i64 gClosedGot = -2;
i32 gClosing = -1;

void *waitsOnClosing(void *unused) {
    gClosedGot = coro.wait(gClosing, libc.POLLIN, 3000);
    return null;
}

void *closesIt(void *unused) {
    coro.sleep(30);
    coro.close(gClosing);
    return null;
}

i32 main() {
    printf("Test - coro loop on epoll/kqueue: ");

    /* 200 pairs, 400 coroutines, one loop: more fds than macOS allows by
     * default (256), so raise the soft limit as a server would */
    libc.rlimit lim;
    libc.getrlimit(libc.RLIMIT_NOFILE, &lim);
    if (lim.rlim_cur < 1024) {
        lim.rlim_cur = lim.rlim_max < 1024 ? lim.rlim_max : 1024;
        libc.setrlimit(libc.RLIMIT_NOFILE, &lim);
    }
    bool made = true;
    for (i64 i = 0; i < NPAIRS; i++) {
        i32 sv[2];
        if (socketpair(libc.AF_UNIX, libc.SOCK_STREAM, 0, sv) != 0) { made = false; break; }
        gA[i] = sv[0];
        gB[i] = sv[1];
        net.setNonBlocking(sv[0]);
        net.setNonBlocking(sv[1]);
    }
    check(made, "200 socket pairs");
    i64 t0 = date.monoMs();
    for (i64 i = 0; i < NPAIRS; i++) {
        coro.go(&echoer, (void *)i);
        coro.go(&pinger, (void *)i);
    }
    coro.run();
    check(gEchoed == NPAIRS * 10, "2000 pings echoed over 200 connections");
    check(date.monoMs() - t0 < 5000, "without waiting for any timeout");
    for (i64 i = 0; i < NPAIRS; i++) {
        libc.close(gA[i]);
        libc.close(gB[i]);
    }

    /* reader + writer on one fd */
    check(socketpair(libc.AF_UNIX, libc.SOCK_STREAM, 0, gPair) == 0, "a pair");
    coro.go(&reader);
    coro.go(&writer);
    coro.run();
    check((gWriteGot & libc.POLLOUT) != 0 && (gReadGot & libc.POLLIN) != 0,
          "the writer gets POLLOUT and the reader POLLIN on one fd");
    check(gLog == 12, "the writer first, the reader when data came");
    u8 drain[8];
    libc.recv(gPair[0], drain, 8, 0);

    coro.go(&lateFire);
    coro.run();
    check(gTimedOut == 0, "a wait times out with 0");
    check((gAfter & libc.POLLIN) != 0, "a later wait on the same fd is answered");
    libc.close(gPair[0]);
    libc.close(gPair[1]);

    /* a regular file is always ready */
    String path = str.format("./ktm-628-%d", libc.getpid());
    defer path.free();
    fs.writeAll(path, "data");
    gFileFd = libc.open(path.cstr(), libc.O_RDONLY, 0);
    coro.go(&fileWait);
    coro.run();
    check((gFileGot & libc.POLLIN) != 0, "a regular file answers at once");
    libc.close(gFileFd);
    fs.remove(path);

    /* coro.close of an fd still waited on: POLLNVAL, as poll says */
    i32 sv[2];
    socketpair(libc.AF_UNIX, libc.SOCK_STREAM, 0, sv);
    gClosing = sv[0];
    t0 = date.monoMs();
    coro.go(&waitsOnClosing);
    coro.go(&closesIt);
    coro.run();
    check((gClosedGot & libc.POLLNVAL) != 0 && date.monoMs() - t0 < 2000,
          "coro.close wakes the coroutine waiting on that fd");
    libc.close(sv[1]);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
