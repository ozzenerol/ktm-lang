import cstr, libc, mem, net, test;

/* the String forms of net's address functions, into a C buffer here */
bool copyOut(String *s, u8 *buf, i64 size) {
    bool ok = s.ptr && s.len < size;
    if (ok) { cstr.copy(buf, s.cstr()); }
    s.free();
    return ok;
}
bool peerInto(i32 fd, u8 *buf, i64 size) {
    String s = net.peerAddr(fd);
    return copyOut(&s, buf, size);
}
bool addrInto(libc.sockaddr *sa, i32 fam, u8 *buf, i64 size) {
    String s = net.addrToStr(sa, fam);
    return copyOut(&s, buf, size);
}
i64 recvInto(i32 fd, u8 *buf, i64 len, u8 *from, i64 fromSize, i32 *port) {
    String f;
    i64 n = net.recvFrom(fd, buf[0:len], &f, port);
    if (!copyOut(&f, from, fromSize)) { from[0] = 0; }
    return n;
}

/* The socket address classes had the BSD layout everywhere (a leading
 * length byte), so on Linux the family landed in the wrong byte: unix
 * domain sockets never bound and an accepted socket's IPv4 address
 * couldn't be read. The unix helpers also overflowed sun_path on long
 * paths. std.ktmh had the BSD addrinfo order, the macOS values of
 * SOL_SOCKET, SO_xxx, TCP_xxx and AI_NUMERICSERV, and no IPPROTO_TCP. */

extern "c" i32 getpid();

i64 gCorrect = 0;
void check(bool ok, u8 *what) {
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 main() {
    i64 tests = 28, i, port;
    u32 len;
    i32 srv, cli, acc, one = 1, seven = 7, got = 0;
    u8 path[256], longpath[256], ip[64], msg[8];
    libc.sockaddr_un un;
    libc.sockaddr_in sin;
    libc.sockaddr_in6 sin6;
    printf("Test - unix domain sockets and socket address layouts: ");

    /* sizes and offsets of the C structs */
    check(sizeof(libc.sockaddr) == 16, "sizeof(sockaddr)");
    check(sizeof(libc.sockaddr_in) == 16, "sizeof(sockaddr_in)");
    check(sizeof(libc.sockaddr_in6) == 28, "sizeof(sockaddr_in6)");
    check(sizeof(libc.sockaddr_storage) == 128, "sizeof(sockaddr_storage)");
    check((u8 *)(&sin.sin_port) - (u8 *)(&sin) == 2, "sin_port offset");
    check((u8 *)(&sin.sin_addr) - (u8 *)(&sin) == 4, "sin_addr offset");
    check((u8 *)(&sin6.sin6_addr) - (u8 *)(&sin6) == 8, "sin6_addr offset");
#ifdef IS_LINUX
    check(sizeof(libc.sockaddr_un) == 110, "sizeof(sockaddr_un)");
    check((u8 *)(&un.sun_path) - (u8 *)(&un) == 2, "sun_path offset");
#else
    check(sizeof(libc.sockaddr_un) == 106, "sizeof(sockaddr_un)");
    check((u8 *)(&un.sun_path) - (u8 *)(&un) == 2, "sun_path offset");
#endif

    /* a real AF_UNIX server and client in one process */
    cstr.formatTo(path, "./ktm183-%d.sock", getpid());
    srv = net.unixListen(cstr.view(path), 4);
    check(srv > 0, "unix server");
    cli = net.unixDial(cstr.view(path));
    check(cli > 0, "unix client");
    acc = libc.accept(srv, null, null);
    check(acc > 0, "accept");
    check(libc.write(cli, "ping", 4) == 4 && libc.read(acc, msg, 4) == 4 && !cstr.cmpLen(msg, "ping", 4), "client -> server");
    check(libc.write(acc, "pong", 4) == 4 && libc.read(cli, msg, 4) == 4 && !cstr.cmpLen(msg, "pong", 4), "server -> client");
    len = sizeof(libc.sockaddr_un);
    mem.set(&un, 0, sizeof(libc.sockaddr_un));
    check(libc.getsockname(srv, (libc.sockaddr *)(&un), &len) == 0 && un.sun_family == libc.AF_UNIX &&
                !cstr.cmp(un.sun_path, path), "getsockname of the server");
    libc.close(acc); libc.close(cli); libc.close(srv);
    libc.unlink(path);

    /* the longest path that fits works, one more byte is refused, not copied */
    len = sizeof(libc.sockaddr_un) - 2 - 1;
    cstr.formatTo(longpath, "./ktm183-%d-", getpid());
    for (i = cstr.len(longpath); i < len; i++) { longpath[i] = 'x'; }
    longpath[len] = '\0';
    srv = net.unixListen(cstr.view(longpath), 4);
    check(srv > 0, "longest path server");
    cli = net.unixDial(cstr.view(longpath));
    check(cli > 0, "longest path client");
    libc.close(cli); libc.close(srv);
    libc.unlink(longpath);
    longpath[len] = 'x';
    longpath[len + 1] = '\0';
    check(net.unixListen(cstr.view(longpath), 4) == -1, "path one byte too long");
    for (i = len + 1; i < 200; i++) { longpath[i] = 'y'; }
    longpath[200] = '\0';
    check(net.unixListen(cstr.view(longpath), 4) == -1 &&
                net.unixDial(cstr.view(longpath)) == -1, "200-byte path");
    check(net.unixDial("./ktm183-nobody-listens") == -1, "no server");

    /* IPv4: the family of an accepted socket's peer address */
    srv = net.listen(0, "127.0.0.1", 4);
    len = sizeof(libc.sockaddr_in);
    libc.getsockname(srv, (libc.sockaddr *)(&sin), &len);
    check(sin.sin_family == libc.AF_INET, "sin_family from getsockname");
    port = ((u8 *)(&sin.sin_port))[0] << 8 | ((u8 *)(&sin.sin_port))[1];
    cli = net.dial("127.0.0.1", port);
    acc = libc.accept(srv, null, null);
    check(peerInto(acc, ip, sizeof(ip)) && !cstr.cmp(ip, "127.0.0.1"),
                "peer address of an accepted socket");

    /* std.ktmh constants and addrinfo field order */
    check(libc.setsockopt(acc, libc.SOL_SOCKET, libc.SO_KEEPALIVE, &one, sizeof(i32)) == 0, "SOL_SOCKET/SO_KEEPALIVE");
    len = sizeof(i32);
    check(libc.getsockopt(acc, libc.SOL_SOCKET, libc.SO_KEEPALIVE, &got, &len) == 0 && got != 0, "getsockopt");
    got = 0;
    len = sizeof(i32);
    check(libc.setsockopt(acc, libc.IPPROTO_TCP, libc.TCP_KEEPINTVL, &seven, sizeof(i32)) == 0 &&
                libc.getsockopt(acc, libc.IPPROTO_TCP, libc.TCP_KEEPINTVL, &got, &len) == 0 && got == 7,
                "IPPROTO_TCP/TCP_KEEPINTVL");
    got = 0;
    len = sizeof(i32);
    check(libc.setsockopt(acc, libc.IPPROTO_TCP, libc.TCP_NODELAY, &one, sizeof(i32)) == 0 &&
                libc.getsockopt(acc, libc.IPPROTO_TCP, libc.TCP_NODELAY, &got, &len) == 0 && got != 0,
                "TCP_NODELAY");
    libc.close(acc); libc.close(cli); libc.close(srv);

    libc.addrinfo hints, *res;
    mem.set(&hints, 0, sizeof(libc.addrinfo));
    hints.ai_family = libc.AF_INET;
    hints.ai_socktype = libc.SOCK_STREAM;
    check(libc.getaddrinfo("127.0.0.1", "80", &hints, &res) == 0 && res.ai_addr.sa_family == libc.AF_INET &&
                ((libc.sockaddr_in *)(res.ai_addr)).sin_addr.s_addr == libc.inet_addr("127.0.0.1"), "addrinfo");
    libc.freeaddrinfo(res);
    hints.ai_flags = libc.AI_NUMERICHOST | libc.AI_NUMERICSERV;
    res = null;
    check(libc.getaddrinfo("127.0.0.1", "80", &hints, &res) == 0, "AI_NUMERICSERV");
    if (res) { libc.freeaddrinfo(res); }

    test.printResult(gCorrect, tests);
    printf("====\n");
    return 0;
}
