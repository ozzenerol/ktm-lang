import libc, net, net.http, str, test;

/* net.http (v0.21.0): values from middleware to handlers: r.set/get with
 * memory from r.alloc, setInt/getInt, has, replacing a key, the limit;
 * through a Mux under test and on a real server, where the second request
 * of a kept-alive connection starts with no values. */

extern "c" i32 pthread_create(void *t, void *attr, void *start, void *arg);
extern "c" i32 pthread_join(void *t, void **ret);
extern "c" i32 usleep(u32 us);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

struct User {
    i64 id;
    u8[] name;
};

/* auth: "Bearer ada" -> the user ada, id 7 */
bool auth(http.Writer *w, http.Request *r) {
    if (str.equals(r.header("Authorization"), "Bearer ada")) {
        User *u = r.alloc(sizeof(User));
        u.id = 7;
        u.name = "ada";
        r.set("user", u);
        r.setInt("userId", u.id);
    }
    return true;
}

void me(http.Writer *w, http.Request *r) {
    User *u = r.get("user");
    if (!u) {
        w.error(401, "who are you?");
        return;
    }
    w.text(200, "%.*s %d %d", u.name.len, u.name.ptr, r.getInt("userId"), r.has("user"));
}

i32 freePort() {
    i32 fd = net.listen(0, "127.0.0.1", 1);
    i32 port = net.localPort(fd);
    libc.close(fd);
    return port;
}

i32 gPort;
String gAll;

void *drive(void *unused) {
    i32 fd = -1;
    for (i64 i = 0; i < 200 && fd < 0; i++) {
        fd = net.dial("127.0.0.1", gPort);
        if (fd < 0) { usleep(10000); }
    }
    net.setRecvTimeout(fd, 5000);
    net.send(fd, "GET /me HTTP/1.1\r\nAuthorization: Bearer ada\r\n\r\nGET /me HTTP/1.1\r\nConnection: close\r\n\r\n");
    u8 buf[4096];
    i64 n;
    while ((n = libc.recv(fd, buf, sizeof(buf), 0)) > 0) { gAll.append(buf[0:n]); }
    libc.close(fd);
    http.shutdown();
    return null;
}

i32 main() {
    printf("Test - http request values: ");
    http.Mux mux;
    mux.use(&auth);
    mux.handle("GET /me", &me);

    http.Request r = http.testRequest("GET", "/me");
    r.addHeader("Authorization", "Bearer ada");
    http.Writer w = http.recorder();
    mux.serve(&w, &r);
    check(w.code == 200 && str.equals(w.body[0:], "ada 7 1"), "middleware hands the handler the user");
    w.free();
    r.free();
    r = http.testRequest("GET", "/me");
    w = http.recorder();
    mux.serve(&w, &r);
    check(w.code == 401, "no Authorization: no user");
    check(!r.has("user") && r.get("user") == null && r.getInt("userId", -1) == -1, "get, getInt and has when unset");
    r.set("k", (void *)1);
    r.set("k", (void *)2);
    r.setInt("n", 5);
    r.setInt("n", 6);
    check(r.get("k") == (void *)2 && r.getInt("n") == 6 && r.nvalues == 2, "a set replaces the key's value");
    bool threw = false;
    try {
        for (i64 i = 0; i < 20; i++) {
            u8 *key = r.alloc(8);
            key[0] = 'a' + i;
            r.setInt(key[0:1], i);
        }
    } catch (Error e) { threw = e.code == http.ERR_HTTP; }
    check(threw && r.nvalues == http.MAX_VALUES, "more than MAX_VALUES throws");
    w.free();
    r.free();

    gPort = freePort();
    http.Options o;
    o.threads = 1;
    o.bindaddr = "127.0.0.1";
    o.noSignals = true;
    void *t;
    pthread_create(&t, null, &drive, null);
    http.listen(gPort, &mux, null, &o);
    pthread_join(t, null);
    i64 first = str.find(gAll[0:], "ada 7 1");
    i64 second = str.find(gAll[0:], "HTTP/1.1 401");
    check(first > 0 && second > first, "on a server: the next request of the connection starts with no values");
    gAll.free();
    mux.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
