import test;

import libc, mem, net;

/* NetConnect gave up when connecting to the first address a name resolved
 * to failed, instead of trying the next one: "localhost" resolving to ::1
 * and then 127.0.0.1 could not reach a server listening on 127.0.0.1 only
 * (NetCreateServer did the same when SO_REUSEADDR failed). The two
 * family checks need NetConnect to reach IPv6 addresses, ::1 to be
 * bindable and localhost to resolve to both families; otherwise they are
 * skipped. Only loopback addresses and kernel-chosen ports are used. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 portFn(i32 sockfd) {
    libc.sockaddr_storage ss;
    u32 len = sizeof(libc.sockaddr_storage);
    if (sockfd == -1 || libc.getsockname(sockfd, (libc.sockaddr *)(&ss), &len) == -1) { return -1; }
    u16 p = ((libc.sockaddr_in *)(&ss)).sin_port;
    return ((p & 0xff) << 8) | (p >> 8);
}

i32 listen6()
{// A server on ::1 only, -1 if IPv6 isn't there
    libc.sockaddr_in6 a;
    i32 fd = libc.socket(libc.AF_INET6, libc.SOCK_STREAM, 0);
    if (fd == -1) { return -1; }
    mem.set(&a, 0, sizeof(libc.sockaddr_in6));
#ifndef IS_LINUX
    a.sin6_len = sizeof(libc.sockaddr_in6);
#endif
    a.sin6_family = libc.AF_INET6;
    libc.inet_pton(libc.AF_INET6, "::1", &a.sin6_addr);
    if (libc.bind(fd, (libc.sockaddr *)(&a), sizeof(libc.sockaddr_in6)) == -1 || libc.listen(fd, 4) == -1) {
        libc.close(fd);
        return -1;
    }
    return fd;
}

bool resolves(u8 *name, i32 family) {
    libc.addrinfo hints, *res = null;
    mem.set(&hints, 0, sizeof(libc.addrinfo));
    hints.ai_family = family;
    hints.ai_socktype = libc.SOCK_STREAM;
    if (libc.getaddrinfo(name, "80", &hints, &res) != 0) { return false; }
    libc.freeaddrinfo(res);
    return true;
}

bool reach(u8 *name, i32 srv)
{// NetConnect(name) gets to srv
    i32 c = net.dial(name, portFn(srv));
    if (c == -1) { return false; }
    i32 a = net.accept(srv);
    bool ok = a != -1;
    if (a != -1) { libc.close(a); }
    libc.close(c);
    return ok;
}

i32 lowestFreeFd() {
    i32 fd = libc.open("/dev/null", libc.O_RDONLY);
    if (fd != -1) { libc.close(fd); }
    return fd;
}

i32 main() {
    printf("Test - NetConnect tries every address: ");

    i32 s4 = net.listen(0, "127.0.0.1", 4);
    check(s4 != -1, "IPv4 server");
    check(reach("127.0.0.1", s4), "connect to 127.0.0.1");
    check(reach("localhost", s4), "localhost reaches an IPv4-only server");

    i32 s6 = listen6();
    if (s6 != -1 && reach("::1", s6) &&
            resolves("localhost", libc.AF_INET) && resolves("localhost", libc.AF_INET6)) {
        /* whichever family localhost lists first, both servers are reached */
        check(reach("localhost", s6), "localhost reaches an IPv6-only server");
        check(reach("localhost", s4), "localhost reaches an IPv4-only server again");
    }

    /* nobody listening on any of localhost's addresses: -1, nothing leaked */
    i64 port = portFn(s4);
    libc.close(s4);
    if (s6 != -1) { libc.close(s6); }
    i32 fdBefore = lowestFreeFd();
    check(net.dial("localhost", port) == -1, "closed port gives -1");
    check(lowestFreeFd() == fdBefore, "no socket leaked");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
