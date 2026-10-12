import coro, date, libc, net, test;

/* coro.accept after a rest out of fds (v0.5.0): when the 2 s timer ends
 * the rest while fds are still full, it checks with dup() first and
 * rests again instead of calling accept(), which on macOS would drop the
 * client queued meanwhile. That client is accepted once a coro.close()
 * frees an fd. (Linux keeps a client queued when accept fails, so there
 * the drop can't happen; the check guards macOS.) */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 gListen, gPort, gLater = -1;
i32 gSpare[64];
i64 gNspare = 0;
bool gSawA = false;
i64 gAccepted = 0, gRestMs = 0;

/* take every fd that is left */
void fill() {
    i32 fd;
    while (gNspare < 64 && (fd = libc.dup(gListen)) >= 0) { gSpare[gNspare++] = fd; }
}

/* the byte a client sent, 0 if none came */
u8 firstByte(i32 fd) {
    u8 b = 0;
    if (coro.wait(fd, libc.POLLIN, 1000) > 0 && libc.recv(fd, &b, 1, 0) != 1) { b = 0; }
    return b;
}

void *acceptor(void *arg) {
    i64 t0 = date.monoMs();
    i32 fd = coro.accept(gListen, 6000);     /* rests: out of fds */
    gRestMs = date.monoMs() - t0;
    while (fd >= 0) {
        gAccepted++;
        if (firstByte(fd) == 'A') { gSawA = true; }
        coro.close(fd);
        fd = coro.accept(gListen, 300);
    }
    return null;
}

/* while the acceptor rests: a new client, then past the 2 s timer,
 * then fds freed */
void *later(void *arg) {
    coro.sleep(100);
    libc.close(gSpare[--gNspare]);          /* one fd for the client... */
    gLater = net.dial("127.0.0.1", gPort);
    if (gLater >= 0) { libc.send(gLater, "A", 1, 0); }
    coro.sleep(2400);                       /* ...the timer ends the rest here */
    coro.close(gSpare[--gNspare]);
    coro.close(gSpare[--gNspare]);
    return null;
}

i32 main() {
    printf("Test - coro accept probe: ");
    gListen = net.listen(0, "127.0.0.1", 16, libc.AF_INET, true);
    gPort = net.localPort(gListen);
    /* the first client the out-of-fds accept meets (macOS drops it) */
    i32 first = net.dial("127.0.0.1", gPort);
    libc.send(first, "S", 1, 0);

    libc.rlimit old, low;
    libc.getrlimit(libc.RLIMIT_NOFILE, &old);
    i32 probe = libc.dup(gListen);
    libc.close(probe);
    low.rlim_cur = probe + 8;
    low.rlim_max = old.rlim_max;
    check(libc.setrlimit(libc.RLIMIT_NOFILE, &low) == 0, "RLIMIT_NOFILE lowered");
    fill();
    check(gNspare >= 3 && libc.dup(gListen) == -1, "every fd taken");

    coro.go(&acceptor);
    coro.go(&later);
    coro.run();
    check(gLater >= 0, "the second client connected during the rest");
    check(gRestMs >= 2300, "accept waited for the close, past the 2 s timer");
    check(gSawA, "the client queued during the rest was accepted, not dropped");
    check(gAccepted >= 1, "accepted after the close");

    while (gNspare > 0) { libc.close(gSpare[--gNspare]); }
    libc.setrlimit(libc.RLIMIT_NOFILE, &old);
    if (gLater >= 0) { libc.close(gLater); }
    libc.close(first);
    libc.close(gListen);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
