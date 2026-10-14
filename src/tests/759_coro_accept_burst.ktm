import coro, libc, mem, net, os, test;

/* A burst of clients is accepted without a turn of the loop per client
 * (v0.19.0): 120 clients connect at once while another coroutine keeps
 * the loop turning (coro.sleep(0) in a loop), and coro.accept takes them
 * all in a few turns. With io_uring it used to submit an ACCEPT for each
 * one, a turn each, so under load (bench/http, 1024 connections) the last
 * client waited over a second to be accepted; now a queue of 64 or more
 * is taken with plain accepts after 8 ACCEPTs (kernel 6.10 and later; an
 * older one keeps the turn per client, and the uring check is skipped).
 * The turns are checked on Linux only. Each backend runs in a thread of
 * its own. */

i64 gCorrect = 0, gTests = 0;
u8[] gBackend;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed on %s: %s\n", gBackend.ptr, what); }
}

const i64 CONNS = 120;

#ifdef IS_LINUX
extern "c" i32 uname(void *buf);

/* the kernel is 6.10 or later: its ACCEPT says when clients are left queued */
bool kernelHasQueuedFlag() {
    u8 u[6 * 65];        /* struct utsname: release is the third field */
    if (uname(u) != 0) { return false; }
    u8 *p = u + 2 * 65;
    i64 major = 0, minor = 0;
    while (*p >= '0' && *p <= '9') { major = major * 10 + (*p - '0'); p++; }
    if (*p == '.') { p++; }
    while (*p >= '0' && *p <= '9') { minor = minor * 10 + (*p - '0'); p++; }
    return major > 6 || (major == 6 && minor >= 10);
}
#endif

i32 gListen;
i32 gClients[120];
i32 gServed[120];
i64 gAccepted, gTicks, gTicksFirst, gTicksLast;
bool gDone;

/* A connection to the listener that doesn't wait for the handshake (see
 * test 634) */
i32 connectAsync(i32 port) {
    i32 fd = libc.socket(libc.AF_INET, libc.SOCK_STREAM, 0);
    if (fd < 0) { return -1; }
    net.setNonBlocking(fd);
    libc.sockaddr_in a;
    mem.set(&a, 0, sizeof(libc.sockaddr_in));
    a.sin_family = libc.AF_INET;
    a.sin_port = net.htons((u16)port);
    a.sin_addr.s_addr = net.htonl(0x7F000001);
    if (libc.connect(fd, (libc.sockaddr *)&a, sizeof(libc.sockaddr_in)) != 0 &&
        os.errno() != libc.EINPROGRESS) {
        libc.close(fd);
        return -1;
    }
    return fd;
}

/* turns the loop while the acceptor works */
void *ticker(void *unused) {
    while (!gDone) {
        gTicks++;
        coro.sleep(0);
    }
    return null;
}

void *acceptor(void *unused) {
    for (i64 i = 0; i < CONNS; i++) {
        i32 fd = coro.accept(gListen, 10000);
        if (fd < 0) { break; }
        if (i == 0) { gTicksFirst = gTicks; }
        gServed[gAccepted++] = fd;
    }
    gTicksLast = gTicks;
    gDone = true;
    return null;
}

void *scenario(void *unused) {
    gBackend = coro.backend();
    gAccepted = gTicks = gTicksFirst = gTicksLast = 0;
    gDone = false;
    gListen = net.listen(0, "127.0.0.1", 512);
    for (i64 i = 0; i < CONNS; i++) { gClients[i] = connectAsync(net.localPort(gListen)); }
    /* every handshake done, so the whole burst is queued when the
     * acceptor starts (macOS finishes them a little later) */
    libc.pollfd pfds[120];
    for (i64 left = CONNS, round = 0; left > 0 && round < 50; round++) {
        for (i64 i = 0; i < CONNS; i++) {
            pfds[i].fd = gClients[i];
            pfds[i].events = libc.POLLOUT;
            pfds[i].revents = 0;
        }
        libc.poll(pfds, CONNS, 100);
        left = 0;
        for (i64 i = 0; i < CONNS; i++) {
            if (!(pfds[i].revents & libc.POLLOUT)) { left++; }
        }
    }
    coro.go(&ticker);
    coro.go(&acceptor);
    coro.run();
    for (i64 i = 0; i < CONNS; i++) {
        if (gClients[i] >= 0) { libc.close(gClients[i]); }
    }
    for (i64 i = 0; i < gAccepted; i++) { coro.close(gServed[i]); }
    libc.close(gListen);
    return null;
}

void on(u8 *backend) {
    libc.setenv("KTM_CORO_BACKEND", backend, 1);
    libc.pthread_t *th;
    libc.pthread_create(&th, null, &scenario, null);
    libc.pthread_join(th);
    check(gAccepted == CONNS, "every client was accepted");
    /* io_uring before the fix: about one turn per client (119) */
    if (gTicksLast - gTicksFirst >= 20) {
        printf("\n  %d turns of the loop for %d accepts", gTicksLast - gTicksFirst, CONNS);
    }
    /* Linux only: macOS finishes some handshakes late (backlog 128), so
     * the accepts there may wait on the clients, not on the loop */
    bool burstFast = false;
#ifdef IS_LINUX
    burstFast = !(gBackend.len == 5 && !libc.memcmp(gBackend.ptr, "uring", 5)) || kernelHasQueuedFlag();
#endif
    if (burstFast) {
        check(gTicksLast - gTicksFirst < 20, "the burst took a few turns of the loop, not one per client");
    }
}

i32 main() {
    printf("Test - coro accept burst: ");
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
