import date, libc, net, os, test;

/* net.dial's timeout_ms, net.setSendTimeout and net.setRecvTimeout on TCP.
 * Loopback only. A connect that never completes is made with a listening
 * socket whose accept queue is full: Linux and macOS then drop the SYN
 * instead of refusing it, so the connect stays pending. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s (Errno %d)\n", what, os.errno()); }
}

bool isNonBlocking(i32 fd) {
    return (libc.fcntl(fd, libc.F_GETFL, 0) & libc.O_NONBLOCK) != 0;
}

/* A port nothing listens on */
i32 closedPort() {
    i32 fd = net.listen(0, "127.0.0.1", 1);
    i32 port = net.localPort(fd);
    libc.close(fd);
    return port;
}

/* Fill the accept queue of `srv` with connects that are never accepted,
 * until one stays pending; returns how many sockets were used (in fds) */
i64 fillBacklog(i32 srv, i32 *fds, i64 max) {
    libc.sockaddr_in a;
    u32 len = sizeof(libc.sockaddr_in);
    libc.pollfd p;
    i64 i;
    libc.getsockname(srv, (libc.sockaddr *)(&a), &len);
    for (i = 0; i < max; i++) {
        fds[i] = libc.socket(libc.AF_INET, libc.SOCK_STREAM, 0);
        libc.fcntl(fds[i], libc.F_SETFL, libc.fcntl(fds[i], libc.F_GETFL, 0) | libc.O_NONBLOCK);
        libc.connect(fds[i], (libc.sockaddr *)(&a), len);
        p.fd = fds[i];
        p.events = libc.POLLOUT;
        p.revents = 0;
        if (libc.poll(&p, 1, 200) == 0) { return i + 1; }  /* this one is stuck */
    }
    return max;
}

i32 main() {
    i32 srv, cli, acc, port, fds[32];
    i64 t, n, i, nfill, total;
    u8 buf[65536];
    printf("Test - tcp timeouts: ");

    /* a connect with time to spare works, and the socket's mode is the one
     * asked for */
    srv = net.listen(0, "127.0.0.1", 8);
    port = net.localPort(srv);
    cli = net.dial("127.0.0.1", port, false, 2000);
    check(cli != -1 && !isNonBlocking(cli), "connect with a timeout, blocking");
    acc = net.accept(srv);
    check(acc != -1 && net.send(cli, "hi"[0:2]) == 2 && libc.recv(acc, buf, 2, 0) == 2,
                "data flows");
    libc.close(acc);
    libc.close(cli);
    cli = net.dial("127.0.0.1", port, true, 2000);
    check(cli != -1 && isNonBlocking(cli), "connect with a timeout, non-blocking");
    libc.close(net.accept(srv));
    libc.close(cli);

    /* every address the name resolves to is tried: "localhost" may be ::1
     * first, which refuses, then 127.0.0.1 */
    cli = net.dial("localhost", port, false, 2000);
    check(cli != -1, "localhost with a timeout");
    libc.close(net.accept(srv));
    libc.close(cli);
    libc.close(srv);

    /* a refused connect fails at once with ECONNREFUSED */
    t = date.nowMs();
    cli = net.dial("127.0.0.1", closedPort(), false, 2000);
    check(cli == -1 && os.errno() == libc.ECONNREFUSED && date.nowMs() - t < 1500,
                "refused");
    cli = net.dial("127.0.0.1", closedPort());
    check(cli == -1 && os.errno() == libc.ECONNREFUSED, "refused without a timeout");
    check(net.dial("127.0.0.1", 80, false, -1) == -1 && os.errno() == libc.EINVAL,
                "negative timeout");

    /* a connect that never completes gives up with ETIMEDOUT */
    srv = net.listen(0, "127.0.0.1", 1);
    port = net.localPort(srv);
    nfill = fillBacklog(srv, fds, 32);
    t = date.nowMs();
    cli = net.dial("127.0.0.1", port, false, 300);
    t = date.nowMs() - t;
    check(cli == -1 && os.errno() == libc.ETIMEDOUT, "connect times out");
    check(t >= 250 && t < 3000, "after about the timeout");
    if (cli != -1) { libc.close(cli); }
    for (i = 0; i < nfill; i++) { libc.close(fds[i]); }
    libc.close(srv);

    /* recv on a TCP socket times out with EAGAIN */
    srv = net.listen(0, "127.0.0.1", 8);
    cli = net.dial("127.0.0.1", net.localPort(srv));
    acc = net.accept(srv);
    check(net.setRecvTimeout(cli, 150), "NetSetRecvTimeout on TCP");
    t = date.nowMs();
    n = libc.recv(cli, buf, 16, 0);
    t = date.nowMs() - t;
    check(n == -1 && os.errno() == libc.EAGAIN && t >= 100 && t < 3000, "recv times out");

    /* send times out with EAGAIN once the peer stops reading */
    check(net.setSendTimeout(cli, 150), "NetSetSendTimeout");
    check(!net.setSendTimeout(cli, -1) && net.setSendTimeout(cli, 150),
                "negative send timeout");
    t = date.nowMs();
    total = 0;
    n = 0;
    while (n != -1 && date.nowMs() - t < 10000) {
        n = net.send(cli, buf[0:sizeof(buf)]);
        if (n > 0) { total += n; }
    }
    check(n == -1 && os.errno() == libc.EAGAIN && total > 0, "send times out");
    check(date.nowMs() - t < 10000, "send gave up in time");
    libc.close(acc);
    libc.close(cli);
    libc.close(srv);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
