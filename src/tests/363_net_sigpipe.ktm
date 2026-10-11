import test;

import libc, net, os;

/* Writing to a socket whose peer had closed raised SIGPIPE, which ended
 * the program, and there was no way to avoid it: no Net* send helper,
 * no MSG_NOSIGNAL and no signal(). NetSend now passes MSG_NOSIGNAL, and
 * signal(SIGPIPE, SIG_IGN) is available for plain write(). Reaching the
 * end of this test at all is the main check. */

extern "c" i32 socketpair(i32 d, i32 t, i32 p, i32 *sv);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* The repro from the known-issues page: close the accepted socket, then
 * write to the client end twice */
void tcpClosedPeer() {
    i32 srv, cli, conn, i;
    u32 len = sizeof(libc.sockaddr_in);
    i64 rv = 0;
    libc.sockaddr_in sa;

    srv = net.listen(0, "127.0.0.1", 4);
    check(srv != -1, "NetCreateServer");
    check(libc.getsockname(srv, (libc.sockaddr *)&sa, &len) == 0, "getsockname");
    cli = net.dial("127.0.0.1", net.ntohs(sa.sin_port));
    check(cli != -1, "NetConnect");
    conn = net.accept(srv);
    check(conn != -1, "NetAccept");
    libc.close(conn);

    /* The first write may still succeed (the peer answers it with a RST),
     * a later one fails */
    for (i = 0; i < 50 && rv != -1; i++) {
        rv = net.send(cli, "x", 1);
        if (rv != -1) { libc.poll(null, 0, 10); }   // 10 ms
    }
    check(rv == -1, "NetSend to a closed peer returns -1");
    check(os.errno() == libc.EPIPE || os.errno() == libc.ECONNRESET,
            "NetSend: EPIPE or ECONNRESET");
    libc.close(cli);
    libc.close(srv);
}

void unixClosedPeer() {
    i32 sv[2];
    check(socketpair(libc.AF_UNIX, libc.SOCK_STREAM, 0, sv) == 0, "socketpair");
    check(net.send(sv[0], "ok", 2) == 2, "NetSend to an open peer");
    libc.close(sv[1]);
    check(net.send(sv[0], "x", 1) == -1 && os.errno() == libc.EPIPE,
            "NetSend to a closed peer: EPIPE");
    /* Caller flags are kept */
    check(net.send(sv[0], "x", 1, libc.MSG_DONTWAIT) == -1 && os.errno() == libc.EPIPE,
            "NetSend with flags: EPIPE");
    libc.close(sv[0]);
}

void ignoreSigpipe() {
    i32 sv[2];
    check(libc.signal(libc.SIGPIPE, libc.SIG_IGN) != libc.SIG_ERR, "signal(SIGPIPE, SIG_IGN)");
    check(socketpair(libc.AF_UNIX, libc.SOCK_STREAM, 0, sv) == 0, "socketpair 2");
    libc.close(sv[1]);
    check(libc.write(sv[0], "x", 1) == -1 && os.errno() == libc.EPIPE,
            "write with SIGPIPE ignored: EPIPE");
    libc.close(sv[0]);
    check(libc.signal(libc.SIGPIPE, libc.SIG_DFL) == libc.SIG_IGN,
            "signal returns the previous handler");
}

i32 main() {
    printf("Test - net sigpipe: ");
    tcpClosedPeer();
    unixClosedPeer();
    ignoreSigpipe();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
