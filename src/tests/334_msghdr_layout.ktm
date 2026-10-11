import test;

import libc, mem, str;

/* msghdr had msg_iovlen as i32 and msg_controllen as i64 everywhere. On
 * Linux both are size_t, so the upper half of msg_iovlen was whatever was
 * in memory and sendmsg failed; on macOS msg_controllen is a socklen_t, so
 * msg_flags sat at the wrong offset. msg_namelen is a socklen_t (u32) on
 * both: read back after recvmsg it carried stale upper bytes. */

extern "c" i32 socketpair(i32 d, i32 t, i32 p, i32 *sv);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 Off(libc.msghdr *m, void *field) {
    return (u8 *)field - (u8 *)m;
}

void stale(libc.msghdr *m)
{// What an uninitialised stack variable may hold
    mem.set(m, 0xAB, sizeof(libc.msghdr));
}

i32 main() {
    libc.msghdr m;
    libc.iovec io[2];
    i32 sv[2];
    u8 buf[16];
    i64 n;
    printf("Test - msghdr layout: ");

#ifdef IS_LINUX
    check(sizeof(libc.msghdr) == 56, "sizeof(msghdr)");
    check(Off(&m, &m.msg_controllen) == 40, "msg_controllen offset");
    check(Off(&m, &m.msg_flags) == 48, "msg_flags offset");
#else
    Check(sizeof(libc.msghdr) == 48, "sizeof(msghdr)");
    Check(Off(&m, &m.msg_controllen) == 40, "msg_controllen offset");
    Check(Off(&m, &m.msg_flags) == 44, "msg_flags offset");
#endif
    check(Off(&m, &m.msg_iov) == 16, "msg_iov offset");
    check(Off(&m, &m.msg_iovlen) == 24, "msg_iovlen offset");
    check(Off(&m, &m.msg_control) == 32, "msg_control offset");

    /* sendmsg/recvmsg with two buffers, over a stale struct */
    check(socketpair(libc.AF_UNIX, libc.SOCK_STREAM, 0, sv) == 0, "socketpair");
    io[0].iov_base = "hel";
    io[0].iov_len = 3;
    io[1].iov_base = "lo";
    io[1].iov_len = 2;
    stale(&m);
    m.msg_name = null; m.msg_namelen = 0;
    m.msg_iov = io; m.msg_iovlen = 2;
    m.msg_control = null; m.msg_controllen = 0; m.msg_flags = 0;
    n = libc.sendmsg(sv[0], &m, 0);
    check(n == 5, "sendmsg sends both buffers");

    mem.set(buf, 0, sizeof(buf));
    io[0].iov_base = buf;
    io[0].iov_len = 2;
    io[1].iov_base = buf + 2;
    io[1].iov_len = 10;
    stale(&m);
    m.msg_name = null; m.msg_namelen = 0;
    m.msg_iov = io; m.msg_iovlen = 2;
    m.msg_control = null; m.msg_controllen = 0; m.msg_flags = 0;
    n = libc.recvmsg(sv[1], &m, 0);
    check(n == 5 && !str.cmp(buf, "hello"), "recvmsg fills both buffers");
    check(m.msg_flags == 0, "msg_flags after recvmsg");
    libc.close(sv[0]);
    libc.close(sv[1]);

    /* recvmsg writes the sender's address length into msg_namelen */
    i32 u = libc.socket(libc.AF_INET, libc.SOCK_DGRAM, 0);
    libc.sockaddr_in me, from;
    u32 len = sizeof(libc.sockaddr_in);
    mem.set(&me, 0, sizeof(libc.sockaddr_in));
#ifndef IS_LINUX
    me.sin_len = sizeof(libc.sockaddr_in);
#endif
    me.sin_family = libc.AF_INET;
    libc.inet_pton(libc.AF_INET, "127.0.0.1", &me.sin_addr);
    check(u != -1 && libc.bind(u, (libc.sockaddr *)(&me), sizeof(libc.sockaddr_in)) == 0, "bind udp");
    libc.getsockname(u, (libc.sockaddr *)(&me), &len);
    check(libc.sendto(u, "ping", 4, 0, (libc.sockaddr *)(&me), sizeof(libc.sockaddr_in)) == 4, "sendto");
    mem.set(buf, 0, sizeof(buf));
    io[0].iov_base = buf;
    io[0].iov_len = sizeof(buf);
    stale(&m);
    m.msg_name = &from; m.msg_namelen = sizeof(libc.sockaddr_in);
    m.msg_iov = io; m.msg_iovlen = 1;
    m.msg_control = null; m.msg_controllen = 0; m.msg_flags = 0;
    n = libc.recvmsg(u, &m, 0);
    check(n == 4 && !str.cmp(buf, "ping"), "recvmsg udp");
    check(m.msg_namelen == sizeof(libc.sockaddr_in), "msg_namelen after recvmsg");
    check(from.sin_port == me.sin_port, "sender address");
    libc.close(u);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
