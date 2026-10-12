import libc, net, os, str, test;

/* net.Addr (v0.8.0): a UDP server answers each datagram to the binary
 * address it came from (a DNS-style id echo over loopback, IPv4 and,
 * where ::1 binds, IPv6), makeAddr / ip / port / family / equals, and
 * the helpers made public: setBlocking, localAddr, remoteAddr. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s (errno %d)\n", what, os.errno()); }
}

bool ipIs(net.Addr *a, u8[] want) {
    String ip = a.ip();
    bool ok = ip.ptr && str.equals(ip, want);
    if (!ok) { printf("\n  ip [%s] want [%s]", ip, want); }
    ip.free();
    return ok;
}

/* Two clients ask the server; it answers each with its id + "!" sent to
 * the Addr the query came from; each client gets its own answer */
bool echoOver(u8[] loop, i32 fam, u8 *label) {
    i32 srv = net.udpSocket(0, loop, fam);
    if (srv == -1) {
        printf("(%s: no %s, skipped) ", label, loop);
        return true;
    }
    i32 c1 = net.udpSocket(0, loop, fam), c2 = net.udpSocket(0, loop, fam);
    net.setRecvTimeout(srv, 2000);
    net.setRecvTimeout(c1, 2000);
    net.setRecvTimeout(c2, 2000);
    net.Addr to, from, back, c1addr;
    bool ok = net.makeAddr(loop, net.localPort(srv), &to) && to.family() == fam;
    ok = ok && net.sendToAddr(c1, "\x12\x34query", &to) == 7;
    ok = ok && net.sendToAddr(c2, "\xAB\xCDquery", &to) == 7;
    u8 buf[512], ans[512];
    for (i64 i = 0; ok && i < 2; i++) {
        i64 n = net.recvFromAddr(srv, buf, &from);
        ok = n == 7 && from.family() == fam && from.port() > 0;
        ans[0] = buf[0];
        ans[1] = buf[1];
        ans[2] = '!';
        ok = ok && net.sendToAddr(srv, ans[0:3], &from) == 3;
    }
    i64 n1 = net.recvFromAddr(c1, buf, &back);
    ok = ok && n1 == 3 && buf[0] == 0x12 && buf[1] == 0x34 && buf[2] == '!';
    ok = ok && back.port() == net.localPort(srv) && back.equals(&to);
    i64 n2 = net.recvFromAddr(c2, buf, &back);
    ok = ok && n2 == 3 && buf[0] == 0xAB && buf[1] == 0xCD;
    ok = ok && net.localAddr(c1, &c1addr) && c1addr.port() == net.localPort(c1) &&
         !c1addr.equals(&back);
    libc.close(srv);
    libc.close(c1);
    libc.close(c2);
    return ok;
}

i32 main() {
    printf("Test - net.Addr and UDP by address: ");

    net.Addr a, b;
    check(net.makeAddr("192.0.2.7", 53, &a) && a.family() == libc.AF_INET && a.port() == 53 &&
          ipIs(&a, "192.0.2.7"), "makeAddr IPv4");
    check(net.makeAddr("2001:db8::1", 853, &b) && b.family() == libc.AF_INET6 && b.port() == 853 &&
          ipIs(&b, "2001:db8::1"), "makeAddr IPv6");
    check(!a.equals(&b), "different families differ");
    check(net.makeAddr("192.0.2.7", 53, &b) && a.equals(&b), "the same address equals");
    check(net.makeAddr("192.0.2.7", 54, &b) && !a.equals(&b) && a.sameIp(&b),
          "another port differs, the same ip");
    check(net.makeAddr("192.0.2.8", 53, &b) && !a.equals(&b) && !a.sameIp(&b),
          "another address differs");
    check(!net.makeAddr("localhost", 53, &b) && b.len == 0 && b.family() == 0 && b.port() == -1,
          "a name is not an address (no lookup)");
    check(!net.makeAddr("1.2.3.4", 70000, &b) && !net.makeAddr("1.2.3.4", -1, &b), "port range");
    check(!net.makeAddr("", 1, &b) && !net.makeAddr("1.2.3", 1, &b) && !net.makeAddr("::g", 1, &b),
          "not an address");
    String none = b.ip();
    check(none.ptr == null, "ip of an empty Addr");
    i32 u = net.udpSocket(0, "127.0.0.1");
    check(net.sendToAddr(u, "x", &b) == -1, "sendToAddr to an empty Addr");
    libc.close(u);

    check(echoOver("127.0.0.1", libc.AF_INET, "IPv4"), "UDP echo by Addr over IPv4");
    check(echoOver("::1", libc.AF_INET6, "IPv6"), "UDP echo by Addr over IPv6");

    /* the helpers made public */
    i32 srv = net.listen(0, "127.0.0.1", 4);
    i32 cl = net.dial("127.0.0.1", net.localPort(srv));
    i32 conn = net.accept(srv);
    net.Addr la, ra, pa;
    check(net.localAddr(cl, &la) && net.remoteAddr(conn, &ra) && la.equals(&ra),
          "localAddr of one end is remoteAddr of the other");
    check(net.remoteAddr(cl, &pa) && pa.port() == net.localPort(srv) && ipIs(&pa, "127.0.0.1"),
          "remoteAddr: ip and port");
    check(!net.remoteAddr(srv, &pa) && pa.len == 0, "remoteAddr of a listening socket");
    check(net.setNonBlocking(cl) && (libc.fcntl(cl, libc.F_GETFL, 0) & libc.O_NONBLOCK) != 0,
          "setNonBlocking");
    check(net.setBlocking(cl) && (libc.fcntl(cl, libc.F_GETFL, 0) & libc.O_NONBLOCK) == 0,
          "setBlocking");
    check(!net.setBlocking(-1), "setBlocking of a bad fd");
    libc.close(conn);
    libc.close(cl);
    libc.close(srv);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
