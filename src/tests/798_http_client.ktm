import atomic, cstr, fs, json, libc, net, net.http, net.https, net.tls, proc, reflect, str, test;


/* net.http's client (v0.20.0), offline: a small server of the test's own
 * (counting connections, answering with Content-Length, chunked or
 * close-delimited bodies, redirects, a slow answer, a dropped kept-alive
 * connection) and net.https with the CA in tls/; a blank import
 * (`import _ = net.https;`) compiles without an unused-import warning. */

extern "c" i32 pthread_create(void *t, void *attr, void *start, void *arg);
extern "c" i32 pthread_join(void *t, void **ret);
extern "c" i32 usleep(u32 us);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

i32 gListen, gPort;
i64 gAccepts = 0;
i64 gStop = 0;
String gLastMethod, gLastBody;

/* The test's server: one connection at a time, requests answered by path */
void answer(i32 fd, u8[] req, u8[] body, bool *close) {
    i64 sp1 = str.find(req, " ");
    u8[] method = req[0:sp1];
    u8[] rest = req[sp1 + 1:];
    u8[] path = rest[0:str.find(rest, " ")];
    i64 qm = str.find(path, "?");
    if (qm >= 0) { path = path[0:qm]; }
    gLastMethod.free();
    gLastMethod = str.from(method);
    gLastBody.free();
    gLastBody = str.from(body);
    if (str.equals(path, "/hello")) {
        net.send(fd, "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nX-Test: yes\r\n\r\nhello");
    } else if (str.equals(path, "/chunked")) {
        net.send(fd, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nwiki\r\n5;x=1\r\npedia\r\n0\r\nT: 1\r\n\r\n");
    } else if (str.equals(path, "/eof")) {
        net.send(fd, "HTTP/1.0 200 OK\r\n\r\nto the end");
        *close = true;
    } else if (str.equals(path, "/missing")) {
        net.send(fd, "HTTP/1.1 404 Not Found\r\nContent-Length: 4\r\n\r\nnope");
    } else if (str.equals(path, "/go")) {
        net.send(fd, "HTTP/1.1 302 Found\r\nLocation: /hello?from=go\r\nContent-Length: 0\r\n\r\n");
    } else if (str.equals(path, "/see")) {
        net.send(fd, "HTTP/1.1 303 See Other\r\nLocation: /hello\r\nContent-Length: 0\r\n\r\n");
    } else if (str.equals(path, "/keep")) {
        net.send(fd, "HTTP/1.1 307 Temporary Redirect\r\nLocation: /echo\r\nContent-Length: 0\r\n\r\n");
    } else if (str.equals(path, "/loop")) {
        net.send(fd, "HTTP/1.1 302 Found\r\nLocation: /loop\r\nContent-Length: 0\r\n\r\n");
    } else if (str.equals(path, "/echo")) {
        String r = str.format("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\n\r\n%.*s",
                              body.len, body.len, body.ptr);
        net.send(fd, r[0:]);
        r.free();
    } else if (str.equals(path, "/slow")) {
        usleep(1500000);
        net.send(fd, "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nslow");
    } else if (str.equals(path, "/drop")) {
        /* answers, then closes without saying so: the client's next
         * request on the kept-alive connection finds it gone */
        net.send(fd, "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ndrop");
        *close = true;
    } else {
        net.send(fd, "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n");
    }
}

void *server(void *unused) {
    while (!atomic.load(&gStop)) {
        i32 fd = net.accept(gListen);
        if (fd < 0 || atomic.load(&gStop)) { if (fd >= 0) { libc.close(fd); } break; }
        atomic.add(&gAccepts, 1);
        String in;
        u8 buf[4096];
        bool close = false;
        while (!close) {
            i64 end = str.find(in[0:], "\r\n\r\n");
            if (end < 0) {
                i64 n = libc.recv(fd, buf, 4096, 0);
                if (n <= 0) { break; }
                in.append(buf[0:n]);
                continue;
            }
            u8[] head = in[0:end];
            i64 cl = 0;
            i64 at = str.find(head, "Content-Length: ");
            if (at >= 0) {
                i64 e = str.find(head[at:], "\r\n");
                str.toInt(head[at + 16:e < 0 ? head.len : at + e], &cl);
            }
            while (in.len < end + 4 + cl) {
                i64 n = libc.recv(fd, buf, 4096, 0);
                if (n <= 0) { break; }
                in.append(buf[0:n]);
            }
            answer(fd, head, in[end + 4:end + 4 + cl], &close);
            String rest = str.from(in[end + 4 + cl:]);
            in.free();
            in = rest;
        }
        in.free();
        libc.close(fd);
    }
    return null;
}

String url(u8 *path) {
    return str.format("http://127.0.0.1:%d%s", gPort, path);
}

i32 gListen2;
tls.Context *gCtx;

void *tlsOnce(void *unused) {
    i32 fd = net.accept(gListen2);
    tls.Conn *c = gCtx.accept(fd);
    u8 buf[2048];
    c.read(buf[0:], 5000);
    c.write("HTTP/1.1 200 OK\r\nContent-Length: 8\r\nConnection: close\r\n\r\nover tls");
    c.close();
    return null;
}

@json struct Item { i64 id; String name; };

bool throws(u8 *u, http.ClientOptions *o) {
    try {
        http.Response r = http.get(cstr.view(u), o);
        r.free();
    } catch (Error e) {
        return e.code == http.ERR_HTTP;
    }
    return false;
}

i32 main() {
    printf("Test - net.http client: ");
    gListen = net.listen(0, "127.0.0.1", 16);
    gPort = net.localPort(gListen);
    void *t;
    pthread_create(&t, null, &server, null);

    String u = url("/hello");
    http.Response r = http.get(u[0:]);
    check(r.status == 200 && str.equals(r.body[0:], "hello") && str.equals(r.header("x-test"), "yes"), "a GET");
    r.free();
    i64 before = atomic.load(&gAccepts);
    for (i64 i = 0; i < 5; i++) {
        r = http.get(u[0:]);
        r.free();
    }
    check(atomic.load(&gAccepts) == before, "five more requests reuse the kept-alive connection");
    u.free();
    u = url("/chunked");
    r = http.get(u[0:]);
    check(r.status == 200 && str.equals(r.body[0:], "wikipedia"), "a chunked body with an extension and a trailer");
    r.free(); u.free();
    u = url("/eof");
    r = http.get(u[0:]);
    check(str.equals(r.body[0:], "to the end"), "a body to the end of the connection (HTTP/1.0)");
    r.free(); u.free();
    u = url("/missing");
    r = http.get(u[0:]);
    check(r.status == 404 && str.equals(r.body[0:], "nope"), "a 404 is an answer, not an error");
    r.free(); u.free();
    u = url("/go");
    r = http.get(u[0:]);
    check(r.status == 200 && str.equals(r.body[0:], "hello") && str.endsWith(r.url[0:], "/hello?from=go"), "a 302 is followed");
    r.free(); u.free();
    u = url("/see");
    r = http.post(u[0:], "text/plain", "data");
    check(r.status == 200 && str.equals(gLastMethod[0:], "GET"), "a POST's 303 becomes a GET");
    r.free(); u.free();
    u = url("/keep");
    Item it;
    it.id = 7;
    it.name = str.from("pen");
    r = http.postJson(u[0:], reflect.of(it));
    Item back = http.decodeResponse<Item>(&r);
    check(r.status == 200 && str.equals(gLastMethod[0:], "POST") && back.id == 7 && str.equals(back.name[0:], "pen"),
          "a 307 keeps the POST and its body; decodeResponse");
    back.freeDecoded();
    it.name.free();
    r.free(); u.free();
    u = url("/loop");
    http.ClientOptions o;
    o.maxRedirects = 3;
    r = http.get(u[0:], &o);
    check(r.status == 302, "a redirect loop stops after maxRedirects");
    r.free(); u.free();
    u = url("/go");
    o.maxRedirects = -1;
    r = http.get(u[0:], &o);
    check(r.status == 302 && str.equals(r.header("location"), "/hello?from=go"), "maxRedirects -1: the 3xx itself");
    r.free(); u.free();
    /* the server drops a kept-alive connection: the next request retries */
    u = url("/drop");
    r = http.get(u[0:]);
    r.free(); u.free();
    usleep(50000);
    u = url("/hello");
    r = http.get(u[0:]);
    check(r.status == 200, "a dropped kept-alive connection: the request goes on a new one");
    r.free(); u.free();
    http.ClientOptions quick;
    quick.timeoutMs = 300;
    u = url("/slow");
    check(throws(u.cstr(), &quick), "the time limit throws");
    u.free();
    check(throws("ftp://example.com/", null) && throws("not a url", null), "a bad URL throws");
    i32 dead = net.listen(0, "127.0.0.1", 1);
    i32 deadPort = net.localPort(dead);
    libc.close(dead);
    String deadUrl = str.format("http://127.0.0.1:%d/", deadPort);
    check(throws(deadUrl.cstr(), null), "nobody listening throws");
    deadUrl.free();

    /* https://, through the blank import of net.https */
    String dir = proc.getenv("KTM_TEST_SRC");
    if (!dir.len) { dir.free(); dir = str.from("."); }
    String srvCert = str.format("%s/tls/server.pem", dir), srvKey = str.format("%s/tls/server.key", dir);
    String ca = str.format("%s/tls/ca.pem", dir);
    tls.Config sc;
    sc.certFile = srvCert[0:];
    sc.keyFile = srvKey[0:];
    tls.Context *sctx = tls.server(&sc);
    i32 tl = net.listen(0, "127.0.0.1", 4);
    i32 tport = net.localPort(tl);
    tls.Config cc;
    cc.caFile = ca[0:];
    https.setClientConfig(&cc);
    /* one TLS answer from a thread of the test */
    gListen2 = tl;
    gCtx = sctx;
    void *t2;
    pthread_create(&t2, null, &tlsOnce, null);
    String hu = str.format("https://localhost:%d/x", tport);
    r = http.get(hu[0:]);
    check(r.status == 200 && str.equals(r.body[0:], "over tls"), "an https:// request");
    r.free();
    pthread_join(t2, null);
    hu.free(); srvCert.free(); srvKey.free(); ca.free(); dir.free();
    libc.close(tl);

    /* a blank import: imported for what it does, no warning */
    fs.writeAll("__798_blank.ktm", "import _ = net.https;\nvoid main() { }\n");
    String w = proc.shellOutput("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit __798_blank.ktm 2>&1; echo rc=$?");
    check(str.equals(str.trimEnd(w[0:]), "rc=0"), "import _ = net.https; gives no warning");
    if (!str.equals(str.trimEnd(w[0:]), "rc=0")) { printf("  got: %.*s\n", w.len, w.ptr); }
    w.free();
    fs.remove("__798_blank.ktm");

    http.closeIdle();
    atomic.store(&gStop, 1);
    i32 wake = net.dial("127.0.0.1", gPort);
    libc.close(wake);
    pthread_join(t, null);
    libc.close(gListen);
    gLastMethod.free();
    gLastBody.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}

