import libc, net, net.http, str, test;

/* net.http (v0.21.0): Request.remoteAddr and remotePort, the client's
 * address over IPv4 and IPv6, the same on a kept-alive connection's
 * second request; testRequest's (192.0.2.1:1234) and setRemoteAddr. */

extern "c" i32 pthread_create(void *t, void *attr, void *start, void *arg);
extern "c" i32 pthread_join(void *t, void **ret);
extern "c" i32 usleep(u32 us);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

void who(http.Writer *w, http.Request *r) {
    w.text(200, "%.*s %d", r.remoteAddr().len, r.remoteAddr().ptr, r.remotePort());
}

i32 freePort(u8[] host) {
    i32 fd = net.listen(0, host, 1);
    i32 port = net.localPort(fd);
    libc.close(fd);
    return port;
}

i32 gPort;
u8[] gHost;
String gGot[3];
i32 gLocal = 0;

i32 dial() {
    for (i64 i = 0; i < 200; i++) {
        i32 fd = net.dial(gHost, gPort);
        if (fd >= 0) {
            net.setRecvTimeout(fd, 5000);
            return fd;
        }
        usleep(10000);
    }
    return -1;
}

/* Two requests on one connection; the answers' bodies */
void *drive(void *unused) {
    i32 fd = dial();
    gLocal = net.localPort(fd);
    net.send(fd, "GET /who HTTP/1.1\r\nHost: x\r\n\r\nGET /who HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
    String all;
    u8 buf[4096];
    i64 n;
    while ((n = libc.recv(fd, buf, sizeof(buf), 0)) > 0) { all.append(buf[0:n]); }
    libc.close(fd);
    u8[] rest = all[0:];
    for (i64 k = 0; k < 2; k++) {
        i64 at = str.find(rest, "\r\n\r\n");
        if (at < 0) { break; }
        i64 cl = str.find(rest, "Content-Length: ");
        i64 len = 0;
        i64 e = str.find(rest[cl + 16:], "\r\n");
        str.toInt(rest[cl + 16:cl + 16 + e], &len);
        gGot[k] = str.from(rest[at + 4:at + 4 + len]);
        rest = rest[at + 4 + len:];
    }
    all.free();
    http.shutdown();
    return null;
}

bool serve(u8[] host) {
    gHost = host;
    gPort = freePort(host);
    gGot[0].free();
    gGot[1].free();
    http.Mux mux;
    mux.handle("GET /who", &who);
    http.Options o;
    o.threads = 1;
    o.bindaddr = host;
    o.noSignals = true;
    void *t;
    pthread_create(&t, null, &drive, null);
    http.listen(gPort, &mux, null, &o);
    pthread_join(t, null);
    mux.free();
    String want = str.format("%.*s %d", host.len, host.ptr, gLocal);
    bool ok = str.equals(gGot[0][0:], want[0:]) && str.equals(gGot[1][0:], want[0:]);
    if (!ok) { printf("\n  %.*s: got `%.*s` and `%.*s`, want `%.*s`", host.len, host.ptr, gGot[0].len, gGot[0].ptr,
                      gGot[1].len, gGot[1].ptr, want.len, want.ptr); }
    want.free();
    return ok;
}

i32 main() {
    printf("Test - http remoteAddr: ");
    check(serve("127.0.0.1"), "IPv4: the client's address and port, twice on one connection");
    i32 probe = net.listen(0, "::1", 1);
    if (probe >= 0) {
        libc.close(probe);
        check(serve("::1"), "IPv6");
    }

    http.Request r = http.testRequest("GET", "/x");
    check(str.equals(r.remoteAddr(), "192.0.2.1") && r.remotePort() == 1234, "testRequest's address");
    r.setRemoteAddr("2001:db8::7", 443);
    check(str.equals(r.remoteAddr(), "2001:db8::7") && r.remotePort() == 443, "setRemoteAddr");
    r.setRemoteAddr("");
    check(str.equals(r.remoteAddr(), "") && r.remotePort() == 0, "no address");
    r.free();
    http.Writer w = http.recorder();
    r = http.testRequest("GET", "/who");
    who(&w, &r);
    check(str.equals(w.body[0:], "192.0.2.1 1234"), "a handler under test");
    w.free();
    r.free();
    gGot[0].free();
    gGot[1].free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
