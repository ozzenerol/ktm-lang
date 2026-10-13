import coro, date, libc, net, os, test;

/* coro.dial (v0.13.0): a connect that pauses only the coroutine. While
 * a dial waits on a listener whose backlog is full, the loop's other
 * coroutines go on (net.dial's blocking connect would stop them all), and
 * the dial gives up within its time. 300 coroutines dial a listener their
 * own loop accepts, a refused port fails at once, a name resolves, and a
 * bad port is EINVAL. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s (errno %d)\n", what, os.errno()); }
}

const i64 N = 300;
i32 gListen;
i32 gPort;
i64 gAccepted = 0, gEchoed = 0, gDialFailed = 0;

void *echoOne(void *arg) {
    i32 fd = (i32)(i64)arg;
    u8 buf[16];
    i64 n = coro.read(fd, buf[0:16], 5000);
    if (n > 0) { coro.write(fd, buf[0:n]); }
    coro.close(fd);
    return null;
}

void *acceptor(void *unused) {
    while (gAccepted < N) {
        i32 fd = coro.accept(gListen, 5000);
        if (fd < 0) { break; }
        gAccepted++;
        coro.go(&echoOne, (void *)(i64)fd);
    }
    return null;
}

void *dialer(void *unused) {
    i32 fd = coro.dial("127.0.0.1", gPort, 5000);
    if (fd < 0) {
        gDialFailed++;
        return null;
    }
    u8 buf[8];
    if (coro.write(fd, "hi") == 2 && coro.read(fd, buf[0:8], 5000) == 2) { gEchoed++; }
    coro.close(fd);
    return null;
}

i64 gRefused = -2, gRefusedErr = 0, gRefusedMs = 0;
i64 gNamed = -2;
i64 gBadPort = -2, gBadPortErr = 0;

void *oneOffs(void *unused) {
    i32 closed = net.listen(0, "127.0.0.1", 1);
    i32 port = net.localPort(closed);
    libc.close(closed);                          /* nobody listens there now */
    i64 t0 = date.monoMs();
    gRefused = coro.dial("127.0.0.1", port, 2000);
    gRefusedErr = os.errno();
    gRefusedMs = date.monoMs() - t0;
    i32 fd = coro.dial("localhost", gPort, 2000);
    gNamed = fd;
    if (fd >= 0) { coro.close(fd); }
    gBadPort = coro.dial("127.0.0.1", 70000);
    gBadPortErr = os.errno();
    return null;
}

i64 gTicks = 0;
bool gDialDone = false;

/* the loop's other coroutine: it ticks while the dial waits */
void *ticker(void *unused) {
    while (!gDialDone) {
        gTicks++;
        coro.sleep(10);
    }
    return null;
}

i32 gFull;
i32 gFullPort;
i32 gFillers[64];
i64 gNfill = 0;
i64 gFullRes = -2, gFullMs = 0;

void *fullBacklog(void *unused) {
    /* fill the backlog (nobody accepts); the next connect can't finish */
    for (i64 i = 0; i < 64; i++) {
        i32 fd = libc.socket(libc.AF_INET, libc.SOCK_STREAM, 0);
        net.setNonBlocking(fd);
        libc.sockaddr_in a;
        libc.memset(&a, 0, sizeof(libc.sockaddr_in));
        a.sin_family = libc.AF_INET;
        a.sin_port = net.htons((u16)gFullPort);
        a.sin_addr.s_addr = net.htonl(0x7F000001);
        libc.connect(fd, (libc.sockaddr *)&a, sizeof(libc.sockaddr_in));
        gFillers[gNfill++] = fd;
    }
    coro.sleep(100);
    i64 t0 = date.monoMs();
    gFullRes = coro.dial("127.0.0.1", gFullPort, 300);
    gFullMs = date.monoMs() - t0;
    gDialDone = true;
    if (gFullRes >= 0) { coro.close((i32)gFullRes); }
    return null;
}

i32 main() {
    printf("Test - coro.dial: ");
    libc.rlimit lim;
    libc.getrlimit(libc.RLIMIT_NOFILE, &lim);
    if (lim.rlim_cur < 2048) {
        lim.rlim_cur = lim.rlim_max < 2048 ? lim.rlim_max : 2048;
        libc.setrlimit(libc.RLIMIT_NOFILE, &lim);
    }

    gListen = net.listen(0, "127.0.0.1", 512);
    gPort = net.localPort(gListen);
    check(gListen >= 0, "a listener");
    i64 t0 = date.monoMs();
    coro.go(&acceptor);
    for (i64 i = 0; i < N; i++) { coro.go(&dialer); }
    coro.run();
    check(gAccepted == N && gEchoed == N && gDialFailed == 0,
          "300 coroutines dial the loop's own listener, each echoed");
    if (gEchoed != N) { printf("(accepted %d, echoed %d, dial failed %d)", gAccepted, gEchoed, gDialFailed); }
    check(date.monoMs() - t0 < 5000, "without waiting for a timeout");

    coro.go(&oneOffs);
    coro.run();
    check(gRefused == -1 && gRefusedErr == libc.ECONNREFUSED && gRefusedMs < 1000,
          "a port nobody listens on: -1 and ECONNREFUSED, at once");
    check(gNamed >= 0, "a name (localhost) resolves and connects");
    check(gBadPort == -1 && gBadPortErr == libc.EINVAL, "a bad port: -1 and EINVAL");
    libc.close(gListen);

    gFull = net.listen(0, "127.0.0.1", 1);
    gFullPort = net.localPort(gFull);
    coro.go(&fullBacklog);
    coro.go(&ticker);
    coro.run();
    /* Linux drops the SYN of a full backlog (the dial times out); macOS
     * may refuse it at once instead: -1 either way, within its time */
    check(gFullRes == -1 && gFullMs < 1500, "a connect that can't finish gives up within its time");
    /* about 40 ticks in 400 ms; a blocked thread would give 1 */
    check(gTicks >= 10, "the loop's other coroutines run while a dial waits");
    for (i64 i = 0; i < gNfill; i++) { libc.close(gFillers[i]); }
    libc.close(gFull);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
