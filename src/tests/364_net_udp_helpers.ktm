import date, libc, mem, net, os, str, test;

/* NetCreateUdpSocket, NetSendTo, NetRecvFrom, NetSetRecvTimeout and
 * NetGetSocketPort: loopback only, ports picked by the kernel. Ends with a
 * DNS-style exchange: a client sends an A query, a server parses it and
 * answers the sender, the client checks the answer. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* Round trip between two sockets of one family: client -> server, then the
 * server answers whoever sent it */
void echo(u8 *ip, i32 fam, u8 *name) {
    i32 srv = net.udpSocket(0, ip, fam), cli = net.udpSocket(0, ip, fam);
    i32 sport, cport, from_port = -1;
    u8 buf[64], from[libc.INET6_ADDRSTRLEN], what[64];
    i64 n;

    if (fam == libc.AF_INET6 && (srv == -1 || cli == -1)) {
        /* no ::1 here (IPv6 disabled): nothing to test */
        if (srv != -1) { libc.close(srv); }
        if (cli != -1) { libc.close(cli); }
        gTests += 6;
        gCorrect += 6;
        return;
    }
    str.formatTo(what, "%s sockets", name);
    check(srv != -1 && cli != -1, what);
    sport = net.localPort(srv);
    cport = net.localPort(cli);
    str.formatTo(what, "%s ports", name);
    check(sport > 0 && cport > 0 && sport != cport, what);

    str.formatTo(what, "%s NetSendTo", name);
    check(net.sendTo(cli, "hello", 5, ip, sport) == 5, what);
    mem.set(buf, 0, sizeof(buf));
    n = net.recvFrom(srv, buf, sizeof(buf), from, sizeof(from), &from_port);
    str.formatTo(what, "%s NetRecvFrom", name);
    check(n == 5 && !str.cmp(buf, "hello"), what);
    str.formatTo(what, "%s sender address and port", name);
    check(!str.cmp(from, ip) && from_port == cport, what);

    /* answer the sender */
    net.sendTo(srv, "world", 5, from, from_port);
    mem.set(buf, 0, sizeof(buf));
    n = net.recvFrom(cli, buf, sizeof(buf));
    str.formatTo(what, "%s reply", name);
    check(n == 5 && !str.cmp(buf, "world"), what);
    libc.close(srv);
    libc.close(cli);
}

/* --- DNS-style request/response ------------------------------------- */

void put16(u8 *p, i64 v) {
    p[0] = (v >> 8) & 0xFF;
    p[1] = v & 0xFF;
}

i64 get16(u8 *p) {
    return (p[0] << 8) | p[1];
}

/* A query for `host`, type A, class IN; returns its length */
i64 dnsQuery(u8 *pkt, i64 id, u8 *host) {
    i64 n = 12, i, start;
    mem.set(pkt, 0, 12);
    put16(pkt, id);
    put16(pkt + 2, 0x0100);  /* recursion desired */
    put16(pkt + 4, 1);       /* one question */
    while (*host) {
        start = n++;
        for (i = 0; host[i] && host[i] != '.'; i++) {
            pkt[n++] = host[i];
        }
        pkt[start] = i;
        host += i;
        if (*host == '.') { host++; }
    }
    pkt[n++] = 0;
    put16(pkt + n, 1);       /* type A */
    put16(pkt + n + 2, 1);   /* class IN */
    return n + 4;
}

/* The server: read one query, answer it with 10.1.2.3 */
bool dnsServe(i32 srv) {
    u8 pkt[512], from[libc.INET6_ADDRSTRLEN], name[256];
    i32 port;
    i64 n = net.recvFrom(srv, pkt, sizeof(pkt), from, sizeof(from), &port);
    i64 q = 12, len = 0;

    if (n < 12 || get16(pkt + 4) != 1) { return false; }
    while (pkt[q]) {  /* the question name, as text */
        if (len) { name[len++] = '.'; }
        mem.copy(name + len, pkt + q + 1, pkt[q]);
        len += pkt[q];
        q += pkt[q] + 1;
    }
    name[len] = '\0';
    if (str.cmp(name, "example.ktm")) { return false; }
    q += 5;  /* the root label, type and class */

    put16(pkt + 2, 0x8180);  /* response, recursion desired + available */
    put16(pkt + 6, 1);       /* one answer */
    put16(pkt + q, 0xC00C);  /* name: pointer to the question's */
    put16(pkt + q + 2, 1);   /* type A */
    put16(pkt + q + 4, 1);   /* class IN */
    put16(pkt + q + 6, 0);   /* TTL 300 */
    put16(pkt + q + 8, 300);
    put16(pkt + q + 10, 4);  /* 4 bytes of data */
    pkt[q + 12] = 10; pkt[q + 13] = 1; pkt[q + 14] = 2; pkt[q + 15] = 3;
    return net.sendTo(srv, pkt, q + 16, from, port) == q + 16;
}

void dns() {
    i32 srv = net.udpSocket(0, "127.0.0.1"), cli = net.udpSocket(0, "*");
    u8 pkt[512];
    i64 n = dnsQuery(pkt, 0xBEEF, "example.ktm"), q;

    check(net.sendTo(cli, pkt, n, "127.0.0.1", net.localPort(srv)) == n, "dns query sent");
    check(dnsServe(srv), "dns server parses and answers");
    net.setRecvTimeout(cli, 2000);
    mem.set(pkt, 0, sizeof(pkt));
    n = net.recvFrom(cli, pkt, sizeof(pkt));
    q = n - 4;
    check(n > 12 && get16(pkt) == 0xBEEF && get16(pkt + 2) == 0x8180 && get16(pkt + 6) == 1 &&
                pkt[q] == 10 && pkt[q + 1] == 1 && pkt[q + 2] == 2 && pkt[q + 3] == 3,
                "dns answer");
    libc.close(srv);
    libc.close(cli);
}

i32 main() {
    i32 fd, fd2;
    u8 buf[4];
    i64 t, n;
    printf("Test - net udp helpers: ");

    echo("127.0.0.1", libc.AF_UNSPEC, "IPv4");
    echo("::1", libc.AF_INET6, "IPv6");

    /* bind addresses and bad arguments, as NetCreateServer */
    fd = net.udpSocket(0, "*");
    check(fd != -1 && net.localPort(fd) > 0, "\"*\" binds any IPv4 address");
    /* an IPv4 socket can't send to an IPv6 address */
    check(net.sendTo(fd, "x", 1, "::1", 9) == -1, "family mismatch");
    check(net.sendTo(fd, "x", 1, "127.0.0.1", 70000) == -1, "bad port in NetSendTo");
    libc.close(fd);
    check(net.udpSocket(-1, "127.0.0.1") == -1 && net.udpSocket(65536, null) == -1,
                "bad port");
    check(net.udpSocket(0, "127.0.0.1", libc.AF_UNIX) == -1, "bad family");
    check(net.udpSocket(0, "::*", libc.AF_INET) == -1, "\"::*\" with AF_INET");
    check(net.udpSocket(0, "not-an-address") == -1, "host names are not looked up");
    fd = net.udpSocket(0, "127.0.0.1");
    fd2 = net.udpSocket(net.localPort(fd), "127.0.0.1");
    check(fd2 == -1, "port in use");
    libc.close(fd);

    /* a datagram longer than the buffer: the rest is dropped */
    fd = net.udpSocket(0, "127.0.0.1");
    net.sendTo(fd, "toolong", 7, "127.0.0.1", net.localPort(fd));
    net.sendTo(fd, "next", 4, "127.0.0.1", net.localPort(fd));
    mem.set(buf, 0, sizeof(buf));
    check(net.recvFrom(fd, buf, 4) == 4 && !str.cmpLen(buf, "tool", 4), "long datagram cut");
    check(net.recvFrom(fd, buf, 4) == 4 && !str.cmpLen(buf, "next", 4), "next datagram whole");

    /* timeout: -1 after about 150 ms instead of waiting forever */
    check(net.setRecvTimeout(fd, 150), "NetSetRecvTimeout");
    t = date.nowMs();
    n = net.recvFrom(fd, buf, 4);
    t = date.nowMs() - t;
    check(n == -1 && os.errno() == libc.EAGAIN && t >= 100 && t < 3000, "recv times out with EAGAIN");
    check(net.setRecvTimeout(fd, 0) && !net.setRecvTimeout(fd, -1), "timeout 0 and -1");
    libc.close(fd);

    /* non-blocking: nothing queued gives -1 at once */
    fd = net.udpSocket(0, "127.0.0.1", libc.AF_INET, true);
    t = date.nowMs();
    check(fd != -1 && net.recvFrom(fd, buf, 4) == -1 && os.errno() == libc.EAGAIN &&
                date.nowMs() - t < 1000, "non-blocking");
    libc.close(fd);
    check(net.localPort(-1) == -1, "NetGetSocketPort on a bad socket");

    dns();

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
