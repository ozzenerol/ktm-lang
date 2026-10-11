import test;

import libc, mem, net, os;

/* The basics socket code needs had no stdlib support: byte order
 * (Htons/Ntohs/Htonl/Ntohl), the C errno (Errno/SetErrno) and its E*
 * names, the MSG_* send/recv flags, SHUT_* and poll(2) with pollfd. */

extern "c" i32 socketpair(i32 d, i32 t, i32 p, i32 *sv);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

void byteOrder() {
    u16 port = 0x1234;
    u32 ip = 0x7F000001;
    check(net.htons(0x1234) == 0x3412, "Htons(0x1234)");
    check(net.htons(0x00FF) == 0xFF00, "Htons(0x00FF)");
    check(net.htons(0xFF00) == 0x00FF, "Htons(0xFF00)");
    check(net.ntohs(net.htons(port)) == port, "Ntohs(Htons(x))");
    check(net.htonl(0x12345678) == 0x78563412, "Htonl(0x12345678)");
    check(net.htonl(0x80) == 0x80000000, "Htonl(0x80): high bit set");
    check(net.htonl(0x80000000) == 0x80, "Htonl(0x80000000)");
    check(net.ntohl(net.htonl(ip)) == ip, "Ntohl(Htonl(x))");
    /* inet_addr gives network order */
    check(libc.inet_addr("127.0.0.1") == net.htonl(ip), "Htonl matches inet_addr");
}

void errnoValues() {
    i32 fd, srv;
    libc.sockaddr_in sa;
    u32 len = sizeof(libc.sockaddr_in);

    os.setErrno(0);
    check(os.errno() == 0, "SetErrno(0)");
    os.setErrno(libc.EINVAL);
    check(os.errno() == libc.EINVAL, "SetErrno(EINVAL)");

    check(libc.close(-1) == -1 && os.errno() == libc.EBADF, "close(-1): EBADF");

    /* Nothing bound on the port: the connection is refused (a bound socket
     * that isn't listening refuses on Linux but times out on macOS) */
    srv = libc.socket(libc.AF_INET, libc.SOCK_STREAM, 0);
    mem.set(&sa, 0, sizeof(libc.sockaddr_in));
#ifndef IS_LINUX
    sa.sin_len = sizeof(libc.sockaddr_in);
#endif
    sa.sin_family = libc.AF_INET;
    sa.sin_port = net.htons(0);
    sa.sin_addr.s_addr = net.htonl(0x7F000001);
    check(libc.bind(srv, (libc.sockaddr *)&sa, sizeof(libc.sockaddr_in)) == 0, "bind 127.0.0.1:0");
    check(libc.getsockname(srv, (libc.sockaddr *)&sa, &len) == 0 && net.ntohs(sa.sin_port) != 0,
            "getsockname gives a port");
    libc.close(srv);
    fd = libc.socket(libc.AF_INET, libc.SOCK_STREAM, 0);
    check(libc.connect(fd, (libc.sockaddr *)&sa, sizeof(libc.sockaddr_in)) == -1 &&
            os.errno() == libc.ECONNREFUSED, "connect: ECONNREFUSED");
    libc.close(fd);
}

void flagsAndPoll() {
    i32 sv[2];
    u8 buf[8];
    libc.pollfd p[2];

    check(sizeof(libc.pollfd) == 8, "sizeof(pollfd)");
    check(socketpair(libc.AF_UNIX, libc.SOCK_STREAM, 0, sv) == 0, "socketpair");

    /* Nothing to read: MSG_DONTWAIT returns at once */
    check(libc.recv(sv[0], buf, 1, libc.MSG_DONTWAIT) == -1 && os.errno() == libc.EAGAIN,
            "recv MSG_DONTWAIT: EAGAIN");
    check(libc.EWOULDBLOCK == libc.EAGAIN, "EWOULDBLOCK");

    p[0].fd = sv[0];
    p[0].events = libc.POLLIN;
    p[0].revents = 0x7F;
    p[1].fd = -1;   /* skipped */
    p[1].events = libc.POLLIN;
    check(libc.poll(p, 2, 0) == 0 && p[0].revents == 0, "poll: nothing to read");
    p[0].events = libc.POLLOUT;
    check(libc.poll(p, 1, 0) == 1 && p[0].revents & libc.POLLOUT, "poll: POLLOUT");

    check(libc.send(sv[1], "hi", 2, 0) == 2, "send");
    p[0].events = libc.POLLIN;
    check(libc.poll(p, 1, 1000) == 1 && p[0].revents & libc.POLLIN, "poll: POLLIN");
    check(libc.recv(sv[0], buf, 2, libc.MSG_PEEK) == 2, "recv MSG_PEEK");
    check(libc.recv(sv[0], buf, 2, libc.MSG_WAITALL) == 2 && buf[0] == 'h' &&
            buf[1] == 'i', "recv after MSG_PEEK reads the same data");

    /* SHUT_WR: the peer reads EOF */
    check(libc.shutdown(sv[1], libc.SHUT_WR) == 0, "shutdown SHUT_WR");
    check(libc.recv(sv[0], buf, 1, 0) == 0, "EOF after SHUT_WR");
    libc.close(sv[0]);
    libc.close(sv[1]);

    /* MSG_NOSIGNAL: a write to a closed peer fails with EPIPE instead of
     * killing the process with SIGPIPE */
    check(socketpair(libc.AF_UNIX, libc.SOCK_STREAM, 0, sv) == 0, "socketpair 2");
    libc.close(sv[1]);
    check(libc.send(sv[0], "x", 1, libc.MSG_NOSIGNAL) == -1 && os.errno() == libc.EPIPE,
            "send MSG_NOSIGNAL to a closed peer: EPIPE");
    libc.close(sv[0]);
}

i32 main() {
    printf("Test - net basics: ");
    byteOrder();
    errnoValues();
    flagsAndPoll();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
