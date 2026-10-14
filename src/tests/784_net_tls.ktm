import atomic, coro, cstr, libc, mem, net, net.tls, proc, str, test;

/* net.tls (v0.20.0), offline: a server and its clients in one program,
 * with the certificates in tls/ (a CA, a server certificate for
 * localhost and 127.0.0.1, an unrelated CA). Threads and blocking
 * sockets, then a coroutine loop; what each Verify mode accepts and
 * refuses; 1 MiB each way; ALPN; the peer's certificate; a read time
 * limit; the errors. */

extern "c" i32 pthread_create(void *t, void *attr, void *start, void *arg);
extern "c" i32 pthread_join(void *t, void **ret);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

/* The certificates: tls/ next to the tests ($KTM_TEST_SRC when the
 * runner runs the test elsewhere) */
String gDir;
u8[] at(u8 *name) {
    String *s = mem.allocZero(sizeof(String));    /* kept to the end */
    *s = str.format("%s/tls/%s", gDir, name);
    return (*s)[0:];
}

tls.Context *gServer;
i32 gListen, gPort;
i64 gAccepted = 0;     /* connections the server thread took */

/* Echoes each connection until the peer closes; serves n of them */
void serveOne(i32 fd) {
    tls.Conn *c;
    try { c = gServer.accept(fd); } catch (Error e) { libc.close(fd); return; }
    u8 buf[16384];
    i64 n;
    while ((n = c.read(buf[0:])) > 0) {
        if (c.write(buf[0:n]) != n) { break; }
    }
    c.close();
}

void *server(void *arg) {
    i64 n = (i64)arg;
    for (i64 i = 0; i < n; i++) {
        i32 fd = net.accept(gListen);
        if (fd < 0) { break; }
        gAccepted++;
        serveOne(fd);
    }
    return null;
}

/* A client handshake against the server with cfg, checking the name
 * host: "" when it worked (and an echo came back), else the error */
String tryClient(tls.Config *cfg, u8[] host) {
    String out;
    tls.Context *ctx = tls.client(cfg);
    defer ctx.free();
    i32 fd = net.dial("127.0.0.1", gPort);
    try {
        tls.Conn *c = ctx.connect(fd, host);
        defer c.close();
        if (c.write("ping") != 4) { out = str.from(c.error()); return out; }
        u8 buf[16];
        i64 n = c.read(buf[0:], 5000);
        if (n != 4) { out = str.format("read %d: %.*s", n, c.error().len, c.error().ptr); }
    } catch (Error e) {
        out = str.from(cstr.view(e.message));
        libc.close(fd);
    }
    return out;
}

i64 gLoopOk = 0;

void *loopServer(void *arg) {
    i32 fd = coro.accept(gListen, 5000);
    if (fd >= 0) { serveOne(fd); }
    return null;
}

void *loopClient(void *arg) {
    tls.Config cfg;
    cfg.caFile = at("ca.pem");
    tls.Conn *c = tls.dial("localhost", gPort, &cfg, 5000);
    String big;
    for (i64 i = 0; i < 300000; i++) { big.appendByte('a' + i % 26); }
    i64 w = c.write(big[0:]);
    String back;
    u8 buf[8192];
    while (back.len < big.len) {
        i64 n = c.read(buf[0:], 5000);
        if (n <= 0) { break; }
        back.append(buf[0:n]);
    }
    if (w == big.len && str.equals(back[0:], big[0:]) && str.equals(c.version(), "TLSv1.3")) { gLoopOk = 1; }
    big.free();
    back.free();
    c.close();
    return null;
}

i32 main() {
    printf("Test - net.tls: ");
    gDir = proc.getenv("KTM_TEST_SRC");
    if (!gDir.len) { gDir.free(); gDir = str.from("."); }
    tls.Config scfg;
    scfg.certFile = at("server.pem");
    scfg.keyFile = at("server.key");
    scfg.alpn = "h2,http/1.1";
    gServer = tls.server(&scfg);
    gListen = net.listen(0, "127.0.0.1", 64);
    gPort = net.localPort(gListen);

    void *t;
    pthread_create(&t, null, &server, (void *)9);

    tls.Config ok;
    ok.caFile = at("ca.pem");
    String r = tryClient(&ok, "localhost");
    check(r.len == 0, "verified by name");
    r.free();
    r = tryClient(&ok, "127.0.0.1");
    check(r.len == 0, "verified by IP address");
    r.free();
    tls.Config other;
    other.caFile = at("other-ca.pem");
    r = tryClient(&other, "localhost");
    check(str.contains(r[0:], "isn't trusted"), "another CA is refused");
    r.free();
    tls.Config chain;
    chain.caFile = at("ca.pem");
    chain.verify = tls.Verify.chain;
    r = tryClient(&chain, "wrong.example");
    check(r.len == 0, "Verify.chain: any name");
    r.free();
    r = tryClient(&ok, "wrong.example");
    check(str.contains(r[0:], "isn't trusted"), "Verify.full: a wrong name is refused");
    r.free();
    tls.Config none;
    none.verify = tls.Verify.none;
    r = tryClient(&none, "localhost");
    check(r.len == 0, "Verify.none: no CA needed");
    r.free();

    /* one connection: 1 MiB each way, ALPN, the certificate, a time limit */
    tls.Config h2;
    h2.caFile = at("ca.pem");
    h2.alpn = "h2";
    tls.Conn *c = tls.dial("localhost", gPort, &h2, 5000);
    check(str.equals(c.alpn(), "h2"), "ALPN");
    i32 md = 0;
    check(c.peerCert(&md).len > 300 && md == 9, "the peer's certificate (DER, SHA-256 signed)");
    String big;
    for (i64 i = 0; i < 1 << 20; i++) { big.appendByte('0' + i % 10); }
    check(c.write(big[0:], 5000) == big.len, "1 MiB written");
    String back;
    u8 buf[16384];
    while (back.len < big.len) {
        i64 n = c.read(buf[0:], 5000);
        if (n <= 0) { break; }
        back.append(buf[0:n]);
    }
    check(str.equals(back[0:], big[0:]), "1 MiB echoed");
    check(c.read(buf[0:], 100) == -1 && str.contains(c.error(), "timed out"), "a read time limit");
    big.free();
    back.free();
    c.close();

    /* not TLS: the handshake fails cleanly */
    i32 plain = net.listen(0, "127.0.0.1", 4);
    i32 plainPort = net.localPort(plain);
    i32 cfd = net.dial("127.0.0.1", plainPort);
    i32 sfd = net.accept(plain);
    net.send(sfd, "HTTP/1.1 400 Bad Request\r\n\r\n");
    libc.close(sfd);
    tls.Context *cl = tls.client(&none);
    bool threw = false;
    try { cl.connect(cfd, "localhost"); } catch (Error e) { threw = e.code == tls.ERR_TLS; }
    check(threw, "a server that doesn't speak TLS: ERR_TLS");
    libc.close(cfd);
    libc.close(plain);
    cl.free();
    threw = false;
    try { tls.Config bad; bad.caFile = at("nope.pem"); tls.client(&bad); } catch (Error e) {
        threw = str.contains(cstr.view(e.message), at("nope.pem"));
    }
    check(threw, "a missing CA file");
    threw = false;
    try { tls.Config nocert; tls.server(&nocert); } catch (Error e) {
        threw = str.contains(cstr.view(e.message), "needs a certificate");
    }
    check(threw, "a server without a certificate");
    threw = false;
    try {
        tls.Config wrong;
        wrong.certFile = at("ca.pem");       /* a certificate whose key isn't this one */
        wrong.keyFile = at("server.key");
        tls.server(&wrong);
    } catch (Error e) {
        threw = str.contains(cstr.view(e.message), "doesn't belong to the certificate");
    }
    check(threw, "a key that doesn't match its certificate");

    /* the rest of the server's 9 connections, then a coroutine loop */
    for (i64 i = atomic.load(&gAccepted); i < 9; i++) {
        i32 fd = net.dial("127.0.0.1", gPort);
        libc.close(fd);
    }
    pthread_join(t, null);
    net.setNonBlocking(gListen);
    coro.go(&loopServer);
    coro.go(&loopClient);
    coro.run();
    check(gLoopOk == 1, "in a coroutine loop: 300 KB echoed over TLS 1.3");
    libc.close(gListen);
    gServer.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
