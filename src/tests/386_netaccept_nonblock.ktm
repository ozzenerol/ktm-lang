import libc, net, test;

/* On macOS BSD's accept() copies O_NONBLOCK from the listening socket, so
 * net.accept(srv) on a non-blocking listener returned a non-blocking socket
 * (recv then gave -1 EAGAIN instead of waiting); Linux's accept doesn't.
 * net.accept now sets the accepted socket's mode from non_blocking on every
 * OS. Checks all four listener x non_blocking combinations, that
 * FD_CLOEXEC is kept and that the socket carries data. */

bool isNonBlocking(i32 fd) {
    return (libc.fcntl(fd, libc.F_GETFL, 0) & libc.O_NONBLOCK) != 0;
}

/* Accept one connection with the listener (non-)blocking and net.accept's
 * non_blocking, and check the result */
bool acceptMode(bool listenerNb, bool non_blocking) {
    i32 srv, cli, acc;
    libc.pollfd p;
    u8 buf[8];
    bool ok = true;

    srv = net.listen(0, "127.0.0.1", 4, libc.AF_INET, listenerNb);
    if (srv == -1) { return false; }
    if (isNonBlocking(srv) != listenerNb) { ok = false; }
    cli = net.dial("127.0.0.1", net.localPort(srv));
    if (cli == -1) {
        libc.close(srv);
        return false;
    }
    p.fd = srv;
    p.events = libc.POLLIN;
    p.revents = 0;
    libc.poll(&p, 1, 5000);

    acc = net.accept(srv, non_blocking);
    if (acc == -1) {
        ok = false;
    } else {
        if (isNonBlocking(acc) != non_blocking) {
            printf("listener %d, non_blocking %d: accepted socket non-blocking %d\n",
                listenerNb, non_blocking, isNonBlocking(acc));
            ok = false;
        }
        if (!(libc.fcntl(acc, libc.F_GETFD, 0) & libc.FD_CLOEXEC)) { ok = false; }
        /* data goes through; a blocking socket waits for it */
        net.send(cli, "ping"[0:4]);
        if (non_blocking) {
            p.fd = acc;
            p.events = libc.POLLIN;
            p.revents = 0;
            libc.poll(&p, 1, 5000);
        }
        if (libc.recv(acc, buf, 4, 0) != 4 || libc.memcmp(buf, "ping", 4)) { ok = false; }
        libc.close(acc);
    }
    libc.close(cli);
    libc.close(srv);
    return ok;
}

i32 main() {
    printf("Test - NetAccept sets the accepted socket's mode: ");
    i64 correct = 0, tests = 0;

    tests++; if (acceptMode(false, false)) { correct++; }
    else { printf("Failed - blocking listener, blocking accept\n"); }
    tests++; if (acceptMode(false, true)) { correct++; }
    else { printf("Failed - blocking listener, non-blocking accept\n"); }
    tests++; if (acceptMode(true, false)) { correct++; }
    else { printf("Failed - non-blocking listener, blocking accept\n"); }
    tests++; if (acceptMode(true, true)) { correct++; }
    else { printf("Failed - non-blocking listener, non-blocking accept\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
