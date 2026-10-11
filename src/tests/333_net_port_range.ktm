#include "testhelper.ktm"

import libc, net;

/* NetCreateServer and NetConnect printed the port into a 6-byte buffer, so
 * a port above 65535 was cut to its first five digits (212345 listened on
 * 21234) and 65536 or a negative port was not rejected. Ports outside
 * 0..65535 now give -1. Only 127.0.0.1 and kernel-chosen ports are used; the server checks
 * pick values whose cut-off form is that port, so even the bug never
 * listens anywhere else. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 localPort(i32 sockfd) {
    libc.sockaddr_in sa;
    u32 len = sizeof(libc.sockaddr_in);
    if (libc.getsockname(sockfd, (libc.sockaddr *)(&sa), &len) == -1) { return -1; }
    u16 p = sa.sin_port;
    return ((p & 0xff) << 8) | (p >> 8);
}

void closeIfOpen(i32 fd) {
    if (fd != -1) { libc.close(fd); }
}

i32 main() {
    i32 srv, s, c;
    i64 port;
    printf("Test - Net port range: ");

    /* port 0 still means "any free port" */
    srv = net.listen(0, "127.0.0.1", 4);
    check(srv != -1, "NetCreateServer on port 0");
    port = localPort(srv);
    check(port > 0 && port <= 65535, "kernel picked a port");

    /* a too long port must not connect to its first five digits */
    c = net.dial("127.0.0.1", port * 10 + 7);
    check(c == -1, "NetConnect to port * 10 + 7");
    closeIfOpen(c);
    c = net.dial("127.0.0.1", port + 65536);
    check(c == -1, "NetConnect to port + 65536");
    closeIfOpen(c);
    c = net.dial("127.0.0.1", 65536);
    check(c == -1, "NetConnect to port 65536");
    closeIfOpen(c);
    c = net.dial("127.0.0.1", -1);
    check(c == -1, "NetConnect to port -1");
    closeIfOpen(c);
    c = net.dial("127.0.0.1", port);
    check(c != -1, "NetConnect to the real port");
    closeIfOpen(c);
    closeIfOpen(srv);

    /* a server must not listen on the first five digits either: the port
     * is free again (just closed), so a cut port would bind */
    s = net.listen(port * 10 + 7, "127.0.0.1", 4);
    check(s == -1, "NetCreateServer on port * 10 + 7");
    closeIfOpen(s);
    s = net.listen(-5, "127.0.0.1", 4);
    check(s == -1, "NetCreateServer on -5");
    closeIfOpen(s);
    s = net.listen(-2147483648, "127.0.0.1", 4);
    check(s == -1, "NetCreateServer on i32 min");
    closeIfOpen(s);

    PrintResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
