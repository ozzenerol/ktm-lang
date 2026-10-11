#include "testhelper.ktm"

import libc, net, str;

/* NetAccept returned bool, so a socket number over 255 was truncated, and
 * NetCreateServer/NetConnect returned 0 (or a getaddrinfo error code) on
 * failure, which looks like a valid socket. They return the i32 socket,
 * or -1 on failure, now. */

/* macOS starts processes with a soft limit of 256 descriptors; raise it
 * so the test can open sockets numbered above 255 there too. */
#ifdef IS_MACOS
    const i64 RLIMIT_NOFILE = 8;
#else
    const i64 RLIMIT_NOFILE = 7;
#endif
extern "c" i32 getrlimit(i32 resource, u64 *rlim);
extern "c" i32 setrlimit(i32 resource, u64 *rlim);

void raiseFdLimit(u64 want) {
    u64 rl[2]; /* rlim_cur, rlim_max */
    if (getrlimit(RLIMIT_NOFILE, rl) == -1 || rl[0] >= want) { return; }
    rl[0] = want;
    if (rl[1] < want) { rl[0] = rl[1]; }
    setrlimit(RLIMIT_NOFILE, rl);
}

/* The port a listening socket is bound to (sin_port is at offset 2 in
 * both the BSD and the Linux layout) */
i64 boundPort(i32 fd) {
    u8 sa[128];
    u32 len = sizeof(sa);
    if (libc.getsockname(fd, (libc.sockaddr *)sa, &len) == -1) { return -1; }
    return sa[2] << 8 | sa[3];
}

i32 main() {
    i64 correct = 0, tests = 12, i, port;
    i32 fillers[300], srv, cli, acc, srv2;
    u8 msg[8];
    printf("Test - NetAccept returns the whole socket number: ");

    /* push new descriptors above 255 (the large frame also made the
     * arm64 JIT load i32 locals without sign extension) */
    raiseFdLimit(1024);
    for (i = 0; i < 300; i++) { fillers[i] = libc.open("/dev/null", libc.O_RDONLY); }
    if (fillers[299] > 255) { correct++; }

    srv = net.listen(0, "127.0.0.1", 4);   /* port 0: any free port */
    if (srv > 255) { correct++; }
    port = boundPort(srv);
    if (port > 0) { correct++; }

    cli = net.dial("127.0.0.1", port);
    if (cli > 255) { correct++; }
    acc = net.accept(srv);
    if (acc > 255 && acc != cli && acc != srv) { correct++; }

    /* the accepted socket really is the other end of `cli` */
    if (libc.write(cli, "hi", 2) == 2 && libc.read(acc, msg, 2) == 2 && !str.cmpLen(msg, "hi", 2)) { correct++; }
    libc.close(acc);

    /* non-blocking accept with nothing pending fails with -1 */
    net.setNonBlocking(srv);
    if (net.accept(srv) == -1) { correct++; }
    if (net.accept(srv, true) == -1) { correct++; }

    /* failures are -1, never 0 */
    if (net.accept(fillers[0]) == -1) { correct++; }            /* not a socket */
    srv2 = net.listen(port, "127.0.0.1", 4);          /* port in use */
    if (srv2 == -1) { correct++; }
    else { libc.close(srv2); }
    libc.close(cli);
    libc.close(srv);
    if (net.dial("127.0.0.1", port) == -1) { correct++; }    /* nobody listening */
    if (net.listen(0, "not-an-address!", 4) == -1) { correct++; } /* getaddrinfo fails */

    for (i = 0; i < 300; i++) { libc.close(fillers[i]); }
    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
