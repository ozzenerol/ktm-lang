import cstr, libc, mem, net, test;

/* net.peerAddr read the peer address into a
 * 16-byte sockaddr, which cuts a sockaddr_in6 short, and NetGetInAddress
 * then read sin6_addr past the end of it: an IPv6 peer came out as
 * garbage. Any family other than AF_INET was treated as AF_INET6. Only
 * loopback, kernel-chosen ports and a mkdtemp directory are used; the
 * IPv6 checks are skipped when ::1 can't be bound. */

extern "c" u8 *mkdtemp(u8 *tmpl);
extern "c" i32 getpid();

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 gSrv, gCli, gAcc;

bool pair(i32 family)
{// A listening loopback socket of `family`, a client and the accepted end
    libc.sockaddr_storage ss;
    u32 len = sizeof(libc.sockaddr_storage);
    mem.set(&ss, 0, sizeof(libc.sockaddr_storage));
    gSrv = gCli = gAcc = -1;
    if (family == libc.AF_INET6) {
        libc.sockaddr_in6 *a6 = (libc.sockaddr_in6 *)(&ss);
#ifndef IS_LINUX
        a6->sin6_len = sizeof(libc.sockaddr_in6);
#endif
        a6.sin6_family = libc.AF_INET6;
        libc.inet_pton(libc.AF_INET6, "::1", &a6.sin6_addr);
        len = sizeof(libc.sockaddr_in6);
    } else {
        libc.sockaddr_in *a4 = (libc.sockaddr_in *)(&ss);
#ifndef IS_LINUX
        a4->sin_len = sizeof(libc.sockaddr_in);
#endif
        a4.sin_family = libc.AF_INET;
        libc.inet_pton(libc.AF_INET, "127.0.0.1", &a4.sin_addr);
        len = sizeof(libc.sockaddr_in);
    }
    if ((gSrv = libc.socket(family, libc.SOCK_STREAM, 0)) == -1) { return false; }
    if (libc.bind(gSrv, (libc.sockaddr *)(&ss), len) == -1) { return false; }
    if (libc.listen(gSrv, 4) == -1) { return false; }
    if (libc.getsockname(gSrv, (libc.sockaddr *)(&ss), &len) == -1) { return false; }
    if ((gCli = libc.socket(family, libc.SOCK_STREAM, 0)) == -1) { return false; }
    if (libc.connect(gCli, (libc.sockaddr *)(&ss), len) == -1) { return false; }
    gAcc = net.accept(gSrv);
    return gAcc != -1;
}

void closePair() {
    if (gAcc != -1) { libc.close(gAcc); }
    if (gCli != -1) { libc.close(gCli); }
    if (gSrv != -1) { libc.close(gSrv); }
}

i32 main() {
    u8 buf[64];
    printf("Test - peer address of an accepted socket: ");

    check(pair(libc.AF_INET), "IPv4 pair");
    mem.set(buf, 0, sizeof(buf));
    check(net.peerAddr(gAcc, buf, sizeof(buf)) &&
                !cstr.cmp(buf, "127.0.0.1"), "IPv4 peer");
    closePair();

    if (pair(libc.AF_INET6)) {
        mem.set(buf, 0, sizeof(buf));
        check(net.peerAddr(gAcc, buf, sizeof(buf)) &&
                    !cstr.cmp(buf, "::1"), "IPv6 peer");
        mem.set(buf, 0, sizeof(buf));
        check(!net.peerAddr(gAcc, buf, 3), "buffer too small");
    }
    closePair();

    /* a unix domain peer has no IP address */
    u8 dir[64], path[128];
    cstr.copy(dir, "./ktm335-XXXXXX");
    check(mkdtemp(dir) != null, "mkdtemp");
    cstr.formatTo(path, "%s/s.sock", dir);
    i32 us = net.unixListen(path, 4);
    i32 uc = net.unixDial(path);
    i32 ua = net.accept(us);
    check(us != -1 && uc != -1 && ua != -1, "unix pair");
    cstr.copy(buf, "untouched");
    check(!net.peerAddr(ua, buf, sizeof(buf)), "unix peer is false");
    check(!net.peerAddr(-1, buf, sizeof(buf)), "bad socket is false");
    libc.close(ua); libc.close(uc); libc.close(us);
    libc.unlink(path);
    libc.rmdir(dir);

    /* only AF_INET and AF_INET6 addresses are read */
    libc.sockaddr_storage ss;
    mem.set(&ss, 0x7f, sizeof(libc.sockaddr_storage));
    ss.ss_family = libc.AF_UNIX;
    check(!net.addrToStr((libc.sockaddr *)(&ss), libc.AF_INET6, buf, sizeof(buf)), "AF_UNIX address");
    check(!net.addrToStr((libc.sockaddr *)(&ss), libc.AF_INET, buf, sizeof(buf)), "family mismatch");
    ss.ss_family = libc.AF_INET;
    libc.inet_pton(libc.AF_INET, "10.1.2.3", &((libc.sockaddr_in *)(&ss)).sin_addr);
    check(net.addrToStr((libc.sockaddr *)(&ss), libc.AF_INET, buf, sizeof(buf)) &&
                !cstr.cmp(buf, "10.1.2.3"), "AF_INET address");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
