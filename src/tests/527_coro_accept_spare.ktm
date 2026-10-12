import coro, libc, net, test;

/* coro.accept for a while after running out of fds (v0.5.0): it accepts
 * only while two fds are free (one for the client, one for its work)
 * and otherwise rests without calling accept(), which on macOS would
 * take the next client off the queue and reset it. One fd freed: no
 * accept; two: one client, then a rest; the next one is accepted when
 * another is freed, so every client queued during the rest arrives. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 gListen;
i32 gSpare[64];
i64 gNspare = 0;
u8 gGot[8];
i64 gN = 0;

/* take every fd that is left */
void fill() {
    i32 fd;
    while (gNspare < 64 && (fd = libc.dup(gListen)) >= 0) { gSpare[gNspare++] = fd; }
}

/* accepts and keeps every client, noting the byte it sent */
void *acceptor(void *arg) {
    i32 fd;
    while ((fd = coro.accept(gListen, 5000)) >= 0) {
        u8 b = '?';
        if (coro.wait(fd, libc.POLLIN, 1000) > 0) { libc.recv(fd, &b, 1, 0); }
        if (gN < 7) { gGot[gN++] = b; }
        gGot[gN] = 0;
    }
    return null;
}

i64 gAfterOne = -1, gAfterTwo = -1;

void *driver(void *arg) {
    coro.sleep(100);                        /* the acceptor ran out and rests */
    coro.close(gSpare[--gNspare]);
    coro.sleep(200);
    gAfterOne = gN;
    coro.close(gSpare[--gNspare]);
    coro.sleep(200);
    gAfterTwo = gN;
    coro.close(gSpare[--gNspare]);          /* one more each: S (Linux), X, Y */
    coro.sleep(200);
    coro.close(gSpare[--gNspare]);
    coro.sleep(200);
    coro.stop();
    return null;
}

i32 main() {
    printf("Test - coro accept spare fd: ");
    gListen = net.listen(0, "127.0.0.1", 16, libc.AF_INET, true);
    i32 port = net.localPort(gListen);
    /* S meets the first, failing accept (macOS drops it); X and Y queue */
    u8 *names = "SXY";
    i32 clients[3];
    for (i64 i = 0; i < 3; i++) {
        clients[i] = net.dial("127.0.0.1", port);
        libc.send(clients[i], names + i, 1, 0);
    }

    libc.rlimit old, low;
    libc.getrlimit(libc.RLIMIT_NOFILE, &old);
    i32 probe = libc.dup(gListen);
    libc.close(probe);
    low.rlim_cur = probe + 8;
    low.rlim_max = old.rlim_max;
    check(libc.setrlimit(libc.RLIMIT_NOFILE, &low) == 0, "RLIMIT_NOFILE lowered");
    fill();
    check(gNspare >= 4 && libc.dup(gListen) == -1, "every fd taken");

    coro.go(&acceptor);
    coro.go(&driver);
    coro.run();
    check(gAfterOne == 0, "one free fd: no accept");
    check(gAfterTwo == 1, "two free fds: one client, then it rests again");
    /* Linux keeps S queued, macOS dropped it at the first accept */
    check(gN >= 2 && gGot[gN - 2] == 'X' && gGot[gN - 1] == 'Y', "X and Y both arrive, in order");

    while (gNspare > 0) { libc.close(gSpare[--gNspare]); }
    libc.setrlimit(libc.RLIMIT_NOFILE, &old);
    for (i64 i = 0; i < 3; i++) { libc.close(clients[i]); }
    libc.close(gListen);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
