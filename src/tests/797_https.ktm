import libc, mem, net, net.http, net.https, net.tls, proc, str, test;

/* net.https (v0.20.0): net.http over TLS, with the certificates in tls/
 * (test 784's): a GET, two requests on one kept-alive TLS connection, a
 * POST body, a plain-HTTP client on the TLS port (dropped, the server
 * goes on), then shutdown. */

extern "c" i32 pthread_create(void *t, void *attr, void *start, void *arg);
extern "c" i32 pthread_join(void *t, void **ret);
extern "c" i32 usleep(u32 us);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

void hello(http.Writer *w, http.Request *r) { w.text(200, "hello %s", r.query("name")); }
void echo(http.Writer *w, http.Request *r) { w.write(r.body); }

String gDir;
i32 gPort;
String gGot[4];

u8[] at(u8 *name) {
    String *s = mem.allocZero(sizeof(String));
    *s = str.format("%s/tls/%s", gDir, name);
    return (*s)[0:];
}

/* one TLS connection: send req, read until the server closes */
String ask(u8[] req) {
    String out;
    tls.Config cfg;
    cfg.caFile = at("ca.pem");
    tls.Conn *c;
    for (i64 i = 0; i < 200; i++) {
        try { c = tls.dial("localhost", gPort, &cfg, 2000); break; } catch (Error e) { usleep(10000); }
    }
    if (!c) { return out; }
    c.write(req);
    u8 buf[4096];
    i64 n;
    while ((n = c.read(buf[0:], 5000)) > 0) { out.append(buf[0:n]); }
    c.close();
    return out;
}

void *drive(void *unused) {
    gGot[0] = ask("GET /hello?name=tls HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
    gGot[1] = ask("GET /hello?name=a HTTP/1.1\r\nHost: x\r\n\r\nGET /hello?name=b HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
    gGot[2] = ask("POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 7\r\nConnection: close\r\n\r\nsecrets");
    /* plain HTTP on the TLS port: the handshake fails, the connection goes */
    i32 fd = net.dial("127.0.0.1", gPort);
    net.send(fd, "GET /hello HTTP/1.1\r\n\r\n");
    u8 buf[256];
    i64 n = libc.recv(fd, buf, 256, 0);
    libc.close(fd);
    gGot[3] = str.format("%d", n > 0 ? 1 : 0);
    http.shutdown();
    return null;
}

i32 main() {
    printf("Test - net.https: ");
    gDir = proc.getenv("KTM_TEST_SRC");
    if (!gDir.len) { gDir.free(); gDir = str.from("."); }
    i32 lfd = net.listen(0, "127.0.0.1", 1);
    gPort = net.localPort(lfd);
    libc.close(lfd);
    http.Mux mux;
    mux.handle("GET /hello", &hello);
    mux.handle("POST /echo", &echo);
    http.Options o;
    o.threads = 2;
    o.bindaddr = "127.0.0.1";
    o.noSignals = true;
    void *t;
    pthread_create(&t, null, &drive, null);
    i32 rc = https.listen(gPort, &mux, at("server.pem"), at("server.key"), null, &o);
    pthread_join(t, null);
    check(rc == 0, "https.listen returns 0 after shutdown");
    check(str.startsWith(gGot[0][0:], "HTTP/1.1 200 OK") && str.endsWith(gGot[0][0:], "hello tls"), "a GET over TLS");
    i64 at1 = str.find(gGot[1][0:], "hello a"), at2 = str.find(gGot[1][0:], "hello b");
    check(at1 > 0 && at2 > at1, "two requests on one kept-alive TLS connection");
    check(str.endsWith(gGot[2][0:], "\r\n\r\nsecrets"), "a POST body over TLS");
    check(str.equals(gGot[3][0:], "0"), "plain HTTP on the TLS port gets nothing back");
    for (i64 i = 0; i < 4; i++) { gGot[i].free(); }
    bool threw = false;
    try { https.listen(gPort, &mux, at("ca.pem"), at("server.key"), null, &o); }
    catch (Error e) { threw = e.code == tls.ERR_TLS; }
    check(threw, "a key that isn't the certificate's throws");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
