import coro, libc, net, test;

/* coro.accept (v0.5.0) over a loopback listener: a client and the server
 * talk in one loop, the accepted fd and the listener are non-blocking,
 * accept returns -1 on its timeout and on coro.stop(), also while it
 * waits. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 gListen = -1, gPort = 0;
u8 gReply[16];
bool gServerNonBlock = false;
i64 gServed = 0;

/* serves one line per client: echoes it upper-cased */
void *server(void *arg) {
    i32 fd = coro.accept(gListen);
    if (fd < 0) { return null; }
    gServerNonBlock = (libc.fcntl(fd, libc.F_GETFL, 0) & libc.O_NONBLOCK) != 0;
    u8 buf[16];
    i64 n = -1;
    if (coro.wait(fd, libc.POLLIN, 2000) > 0) { n = libc.recv(fd, buf, sizeof(buf), 0); }
    for (i64 i = 0; i < n; i++) { buf[i] -= 'a' - 'A'; }
    if (n > 0) { libc.send(fd, buf, n, 0); }
    coro.close(fd);
    gServed++;
    return null;
}

void *client(void *arg) {
    i32 fd = net.dial("127.0.0.1", gPort, true);
    if (fd < 0) { return null; }
    coro.wait(fd, libc.POLLOUT, 2000);
    libc.send(fd, "hi", 2, 0);
    if (coro.wait(fd, libc.POLLIN, 2000) > 0) {
        i64 n = libc.recv(fd, gReply, sizeof(gReply) - 1, 0);
        if (n > 0) { gReply[n] = 0; }
    }
    libc.close(fd);
    return null;
}

i64 gTimedOut = -2, gStopped = -2, gAfter = -2;

void *acceptTimeout(void *arg) {
    gTimedOut = coro.accept(gListen, 30);
    return null;
}

void *acceptStopped(void *arg) {
    gStopped = coro.accept(gListen);
    gAfter = coro.accept(gListen, 1000);
    return null;
}

void *stopLater(void *arg) {
    coro.sleep(20);
    coro.stop();
    return null;
}

i32 main() {
    printf("Test - coro loop accept: ");
    gListen = net.listen(0, "127.0.0.1", 16, libc.AF_INET);
    check(gListen >= 0, "listen on loopback");
    gPort = net.localPort(gListen);

    coro.go(&server);
    coro.go(&client);
    coro.run();
    check(!libc.strcmp(gReply, "HI"), "client and server talk in one loop");
    check(gServerNonBlock, "the accepted fd is non-blocking");
    check((libc.fcntl(gListen, libc.F_GETFL, 0) & libc.O_NONBLOCK) != 0, "the listener is non-blocking");

    /* several clients, one acceptor that serves them in turn */
    gServed = 0;
    for (i64 i = 0; i < 3; i++) {
        coro.go(&server);
        coro.go(&client);
    }
    coro.run();
    check(gServed == 3, "three clients served");

    coro.go(&acceptTimeout);
    coro.run();
    check(gTimedOut == -1, "accept returns -1 when ms passed");

    coro.go(&acceptStopped);
    coro.go(&stopLater);
    coro.run();
    check(gStopped == -1, "stop() ends a waiting accept with -1");
    check(gAfter == -1, "accept after stop() returns -1 at once");

    libc.close(gListen);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
