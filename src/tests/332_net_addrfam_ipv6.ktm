import libc, mem, net, test;

/* NetCreateServer and NetConnect always asked getaddrinfo for AF_INET:
 * the addrfam argument was only looked at for "::*", so a server could
 * not listen on ::1 and NetConnect could not reach an IPv6 address. A
 * numeric bind address that isn't valid ("256.1.1.1") went to DNS.
 * Only loopback addresses and kernel-chosen ports are used; the IPv6
 * checks are skipped when ::1 can't be bound. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 gPort;

i64 family(i32 sockfd)
{// The family of the socket's local address; sets g_port
    libc.sockaddr_storage ss;
    u32 len = sizeof(libc.sockaddr_storage);
    mem.set(&ss, 0, sizeof(libc.sockaddr_storage));
    if (libc.getsockname(sockfd, (libc.sockaddr *)(&ss), &len) == -1) { return -1; }
    u16 p = ((libc.sockaddr_in *)(&ss)).sin_port; /* same offset in sockaddr_in6 */
    gPort = ((p & 0xff) << 8) | (p >> 8);
    return ss.ss_family;
}

bool ipv6Loopback() {
    libc.sockaddr_in6 a;
    i32 fd = libc.socket(libc.AF_INET6, libc.SOCK_STREAM, 0);
    if (fd == -1) { return false; }
    mem.set(&a, 0, sizeof(libc.sockaddr_in6));
    a.sin6_family = libc.AF_INET6;
    libc.inet_pton(libc.AF_INET6, "::1", &a.sin6_addr);
    bool ok = libc.bind(fd, (libc.sockaddr *)(&a), sizeof(libc.sockaddr_in6)) == 0;
    libc.close(fd);
    return ok;
}

bool roundTrip(i32 srv, u8 *addr)
{// NetConnect to the server's port at addr, send a byte through
    u8 b = 0;
    i32 c = net.dial(addr, gPort);
    if (c == -1) { return false; }
    i32 a = net.accept(srv);
    bool ok = a != -1 && libc.write(c, "x", 1) == 1 && libc.read(a, &b, 1) == 1 && b == 'x';
    if (a != -1) { libc.close(a); }
    libc.close(c);
    return ok;
}

void closeIfOpen(i32 fd) {
    if (fd != -1) { libc.close(fd); }
}

i32 main() {
    i32 s;
    printf("Test - Net address families: ");

    s = net.listen(0, "127.0.0.1", 4, libc.AF_INET);
    check(s != -1 && family(s) == libc.AF_INET, "127.0.0.1 with AF_INET");
    check(roundTrip(s, "127.0.0.1"), "connect to 127.0.0.1");
    closeIfOpen(s);

    s = net.listen(0, "127.0.0.1", 4);
    check(s != -1 && family(s) == libc.AF_INET, "127.0.0.1 with AF_UNSPEC");
    closeIfOpen(s);

    s = net.listen(0, "127.0.0.1", 4, libc.AF_INET6);
    check(s == -1, "127.0.0.1 with AF_INET6 fails");
    closeIfOpen(s);
    s = net.listen(0, "::*", 4, libc.AF_INET);
    check(s == -1, "::* with AF_INET fails");
    closeIfOpen(s);
    s = net.listen(0, "127.0.0.1", 4, libc.AF_UNIX);
    check(s == -1, "AF_UNIX is not a TCP family");
    closeIfOpen(s);
    s = net.listen(0, "127.0.0.1", 4, 12345);
    check(s == -1, "unknown family");
    closeIfOpen(s);

    /* a bind address is numeric: no DNS lookup for these */
    s = net.listen(0, "256.1.1.1", 4);
    check(s == -1, "256.1.1.1");
    closeIfOpen(s);
    s = net.listen(0, "not-an-address!", 4);
    check(s == -1, "a host name");
    closeIfOpen(s);

    if (ipv6Loopback()) {
        s = net.listen(0, "::1", 4, libc.AF_INET6);
        check(s != -1 && family(s) == libc.AF_INET6, "::1 with AF_INET6");
        check(roundTrip(s, "::1"), "connect to ::1");
        closeIfOpen(s);

        s = net.listen(0, "::1", 4);
        check(s != -1 && family(s) == libc.AF_INET6, "::1 with AF_UNSPEC");
        check(roundTrip(s, "::1"), "connect to ::1 again");
        closeIfOpen(s);

        s = net.listen(0, "::1", 4, libc.AF_INET);
        check(s == -1, "::1 with AF_INET fails");
        closeIfOpen(s);
    }

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
