import atomic, json, libc, net, net.http, reflect, str, test;

/* net.http (v0.19.0): the server on a real socket, driven by a thread of
 * blocking clients: replies, keep-alive and pipelining, chunked bodies,
 * 100-continue, http.decode (and its 400), a throwing handler's 500,
 * HEAD, HTTP/1.0, the 400 / 413 / 431 / 505 answers, request smuggling
 * (Content-Length with chunked) refused, r.app, and http.shutdown. Then
 * Request and Writer on their own (testRequest / recorder): query and
 * path unescaping, headers, w.error's JSON, a header with a line break. */

extern "c" i32 pthread_create(void *t, void *attr, void *start, void *arg);
extern "c" i32 usleep(u32 us);
extern "c" i32 pthread_join(void *t, void **ret);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what, u8[] got = "") {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n%.*s\n", what, got.len, got.ptr); }
}

@json struct User { i64 id; String name; };

struct App { i64 hits; };

void hello(http.Writer *w, http.Request *r) {
    App *app = r.app;
    atomic.add(&app.hits, 1);
    w.text(200, "hello %s", r.query("name"));
}

void create(http.Writer *w, http.Request *r) {
    User u = http.decode<User>(r);
    u.id = 7;
    w.header("X-Len", "1");
    w.json(201, reflect.of(u));
}

void boom(http.Writer *w, http.Request *r) { throw(1, "kaboom"); }

void echoBody(http.Writer *w, http.Request *r) { w.write(r.body); }

i32 freePort() {
    i32 fd = net.listen(0, "127.0.0.1", 1);
    i32 port = net.localPort(fd);
    libc.close(fd);
    return port;
}

i32 gPort;

i32 dial() {
    for (i64 i = 0; i < 200; i++) {
        i32 fd = net.dial("127.0.0.1", gPort);
        if (fd >= 0) {
            net.setRecvTimeout(fd, 5000);
            return fd;
        }
        usleep(10000);
    }
    return -1;
}

/* Sends req and reads until the server closes (or 5 s pass) */
String ask(u8[] req) {
    String out;
    i32 fd = dial();
    if (fd < 0) { return out; }
    net.send(fd, req);
    u8 buf[65536];
    i64 n;
    while ((n = libc.recv(fd, buf, sizeof(buf), 0)) > 0) { out.append(buf[0:n]); }
    libc.close(fd);
    return out;
}

/* The reply's body (after the blank line) */
u8[] bodyOf(String *s) {
    u8[] all = s.ptr[0:s.len];
    i64 at = str.find(all, "\r\n\r\n");
    return at < 0 ? "" : all[at + 4:all.len];
}

i64 countOf(u8[] s, u8[] what) {
    i64 n = 0, at = 0;
    while (true) {
        i64 i = str.find(s[at:s.len], what);
        if (i < 0) { return n; }
        n++;
        at += i + what.len;
    }
}

bool gDone[20];
String gGot[20];

void *drive(void *unused) {
    gGot[0] = ask("GET /hello?name=a%20b+c HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
    gGot[1] = ask("POST /users HTTP/1.1\r\nContent-Length: 14\r\nConnection: close\r\n\r\n{\"name\":\"Ada\"}");
    gGot[2] = ask("POST /users HTTP/1.1\r\nContent-Length: 8\r\nConnection: close\r\n\r\n{\"name\":");
    gGot[3] = ask("GET /boom HTTP/1.1\r\nConnection: close\r\n\r\n");
    gGot[4] = ask("GET /hello HTTP/1.1\r\n\r\nGET /hello HTTP/1.1\r\n\r\nGET /nope HTTP/1.1\r\nConnection: close\r\n\r\n");
    gGot[5] = ask("POST /echo HTTP/1.1\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                  "3\r\nabc\r\n4;ext=1\r\ndefg\r\n0\r\nX-Trailer: 1\r\n\r\n");
    gGot[6] = ask("HEAD /hello HTTP/1.1\r\nConnection: close\r\n\r\n");
    gGot[7] = ask("GET /hello HTTP/1.0\r\n\r\n");
    gGot[8] = ask("NOT A REQUEST\r\n\r\n");
    gGot[9] = ask("POST /echo HTTP/1.1\r\nContent-Length: 999999999\r\n\r\n");
    gGot[10] = ask("GET /hello HTTP/2.0\r\n\r\n");
    gGot[11] = ask("POST /echo HTTP/1.1\r\nContent-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n");
    gGot[12] = ask("DELETE /hello HTTP/1.1\r\nConnection: close\r\n\r\n");
    /* 100-continue: the body only after the interim reply */
    i32 fd = dial();
    net.send(fd, "POST /echo HTTP/1.1\r\nContent-Length: 2\r\nExpect: 100-continue\r\nConnection: close\r\n\r\n");
    u8 buf[4096];
    i64 n = libc.recv(fd, buf, sizeof(buf), 0);
    gGot[13].append(buf[0:n > 0 ? n : 0]);
    net.send(fd, "hi");
    while ((n = libc.recv(fd, buf, sizeof(buf), 0)) > 0) { gGot[14].append(buf[0:n]); }
    libc.close(fd);
    /* a big header block */
    String big;
    big.append("GET /hello HTTP/1.1\r\nX-Big: ");
    for (i64 i = 0; i < 70000; i++) { big.appendByte('a'); }
    big.append("\r\n\r\n");
    gGot[15] = ask(big[0:big.len]);
    big.free();
    http.shutdown();
    return null;
}

i32 main() {
    printf("Test - net.http server: ");
    gPort = freePort();
    App app;
    http.Mux mux;
    mux.handle("GET /hello", &hello);
    mux.handle("POST /users", &create);
    mux.handle("GET /boom", &boom);
    mux.handle("POST /echo", &echoBody);
    http.Options o;
    o.threads = 2;
    o.bindaddr = "127.0.0.1";
    o.noSignals = true;
    void *t;
    pthread_create(&t, null, &drive, null);
    i32 rc = http.listen(gPort, &mux, &app, &o);
    pthread_join(t, null);
    check(rc == 0, "listen returned 0 after shutdown");

    String *g = gGot;
    check(str.startsWith(g[0][0:g[0].len], "HTTP/1.1 200 OK\r\n") && str.equals(bodyOf(&g[0]), "hello a b c") &&
          str.contains(g[0][0:g[0].len], "Content-Type: text/plain; charset=utf-8\r\n") &&
          str.contains(g[0][0:g[0].len], "Connection: close\r\n"), "GET with a query", g[0][0:g[0].len]);
    check(str.startsWith(g[1][0:g[1].len], "HTTP/1.1 201 Created\r\n") && str.equals(bodyOf(&g[1]), "{\"id\":7,\"name\":\"Ada\"}") &&
          str.contains(g[1][0:g[1].len], "X-Len: 1\r\n") && str.contains(g[1][0:g[1].len], "Content-Type: application/json\r\n"),
          "POST JSON", g[1][0:g[1].len]);
    check(str.startsWith(g[2][0:g[2].len], "HTTP/1.1 400 ") && str.contains(bodyOf(&g[2]), "\"error\":\"1:9: name:"),
          "bad JSON is a 400 with the decode message", g[2][0:g[2].len]);
    check(str.startsWith(g[3][0:g[3].len], "HTTP/1.1 500 ") && str.equals(bodyOf(&g[3]), "{\"error\":\"internal server error\"}"),
          "a throw is a 500", g[3][0:g[3].len]);
    check(countOf(g[4][0:g[4].len], "HTTP/1.1 200 OK") == 2 && countOf(g[4][0:g[4].len], "HTTP/1.1 404 ") == 1,
          "three pipelined requests", g[4][0:g[4].len]);
    check(str.equals(bodyOf(&g[5]), "abcdefg"), "a chunked body with an extension and a trailer", g[5][0:g[5].len]);
    check(str.startsWith(g[6][0:g[6].len], "HTTP/1.1 200 OK") && str.contains(g[6][0:g[6].len], "Content-Length: 6\r\n") &&
          bodyOf(&g[6]).len == 0, "HEAD: the length, no body", g[6][0:g[6].len]);
    check(str.startsWith(g[7][0:g[7].len], "HTTP/1.1 200 OK") && str.contains(g[7][0:g[7].len], "Connection: close\r\n"),
          "HTTP/1.0 closes", g[7][0:g[7].len]);
    check(str.startsWith(g[8][0:g[8].len], "HTTP/1.1 400 "), "garbage is a 400", g[8][0:g[8].len]);
    check(str.startsWith(g[9][0:g[9].len], "HTTP/1.1 413 "), "a body over the limit is a 413", g[9][0:g[9].len]);
    check(str.startsWith(g[10][0:g[10].len], "HTTP/1.1 505 "), "HTTP/2.0 is a 505", g[10][0:g[10].len]);
    check(str.startsWith(g[11][0:g[11].len], "HTTP/1.1 400 "), "Content-Length with chunked is a 400", g[11][0:g[11].len]);
    check(str.startsWith(g[12][0:g[12].len], "HTTP/1.1 405 "), "a wrong method is a 405", g[12][0:g[12].len]);
    check(str.equals(g[13][0:g[13].len], "HTTP/1.1 100 Continue\r\n\r\n"), "100 Continue first", g[13][0:g[13].len]);
    check(str.equals(bodyOf(&g[14]), "hi"), "then the reply", g[14][0:g[14].len]);
    check(str.startsWith(g[15][0:g[15].len], "HTTP/1.1 431 "), "headers over 64 KiB are a 431", g[15][0:g[15].len]);
    check(app.hits == 5, "r.app reached the handler");
    for (i64 i = 0; i < 20; i++) { gGot[i].free(); }

    /* Request and Writer without a server */
    http.Request r = http.testRequest("GET", "/a%2Fb?x=1&y=a+b%21&flag&x=2");
    r.addHeader("Content-Type", "text/html");
    check(str.equals(r.query("x"), "1") && str.equals(r.query("y"), "a b!") && r.hasQuery("flag") &&
          !r.hasQuery("nope") && r.query("nope").len == 0, "query values");
    check(str.equals(r.unescape(r.path), "/a/b") && str.equals(r.path, "/a%2Fb"), "path unescape");
    check(str.equals(r.header("content-type"), "text/html") && r.header("x").len == 0 && r.hasHeader("CONTENT-TYPE"),
          "headers by any case");
    r.setParam("id", "42");
    r.setParam("id", "43");
    check(str.equals(r.param("id"), "43") && r.param("no").len == 0, "params");
    r.free();

    http.Writer w = http.recorder();
    w.write("partial");
    w.error(422, "bad \"name\"\n");
    check(w.code == 422 && str.equals(w.body[0:w.body.len], "{\"error\":\"bad \\\"name\\\"\\n\"}") &&
          str.equals(w.getHeader("content-type"), "application/json"), "w.error", w.body[0:w.body.len]);
    bool threw = false;
    try { w.header("X", "a\r\nInjected: 1"); } catch (Error e) { threw = e.code == http.ERR_HTTP; }
    check(threw, "a header with a line break throws");
    check(str.equals(http.statusText(404), "Not Found") && http.statusText(999).len == 0, "statusText");
    w.free();

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
