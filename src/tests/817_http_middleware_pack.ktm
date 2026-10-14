import atomic, net.http, net.http.middleware, str, test;

/* net.http.middleware (v0.21.0): request IDs (kept or made), the real
 * client address from X-Forwarded-For only through trusted proxies, CORS
 * (simple requests, preflights, refused origins, credentials), rate
 * limiting (per client, Retry-After, exact under 4 threads at once),
 * bearer tokens handing the user to the handler, and mux.useWith. */

extern "c" i32 pthread_create(void *t, void *attr, void *start, void *arg);
extern "c" i32 pthread_join(void *t, void **ret);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

void who(http.Writer *w, http.Request *r) {
    u8[] id = middleware.requestIdOf(r);
    w.text(200, "%.*s|%.*s", r.remoteAddr().len, r.remoteAddr().ptr, id.len, id.ptr);
}

/* A test request through mux; the Writer is the caller's to free */
http.Writer ask(http.Mux *mux, u8[] method, u8[] target, u8[] from, u8[] h1, u8[] v1, u8[] h2 = "", u8[] v2 = "") {
    http.Request r = http.testRequest(method, target);
    if (from.len) { r.setRemoteAddr(from); }
    if (h1.len) { r.addHeader(h1, v1); }
    if (h2.len) { r.addHeader(h2, v2); }
    http.Writer w = http.recorder();
    mux.serve(&w, &r);
    r.free();
    return w;
}

bool hdr(http.Writer *w, u8[] line) { return str.find(w.headers[0:], line) >= 0; }

bool check2(u8[] token, http.Request *r) {
    if (!str.equals(token, "s3cret")) { return false; }
    r.setInt("userId", 42);
    return true;
}

void me(http.Writer *w, http.Request *r) { w.text(200, "user %d", r.getInt("userId")); }

http.Mux gLimited;
i64 gAllowed = 0;
void *hammer(void *arg) {
    for (i64 i = 0; i < 100; i++) {
        http.Writer w = ask(&gLimited, "GET", "/who", "198.51.100.9", "", "");
        if (w.code == 200) { atomic.add(&gAllowed, 1); }
        w.free();
    }
    return null;
}

bool ctxRun(http.Writer *w, http.Request *r, void *ctx) {
    r.setInt("tag", *(i64 *)ctx);
    return true;
}
void tag(http.Writer *w, http.Request *r) { w.text(200, "%d", r.getInt("tag")); }

i32 main() {
    printf("Test - http middleware pack: ");
    http.Mux mux;
    middleware.realIp(&mux, "10.0.0.0/8, 127.0.0.1, ::1");
    middleware.requestId(&mux);
    mux.handle("GET /who", &who);

    http.Writer w = ask(&mux, "GET", "/who", "203.0.113.5", "X-Request-Id", "abc-123");
    check(str.equals(w.body[0:], "203.0.113.5|abc-123") && hdr(&w, "X-Request-Id: abc-123\r\n"), "a client's request ID is kept");
    w.free();
    w = ask(&mux, "GET", "/who", "203.0.113.5", "X-Request-Id", "bad id\x01");
    check(w.body.len == 11 + 1 + 36 && hdr(&w, "X-Request-Id: ") && w.body[11 + 1 + 14] == '7', "a new UUIDv7 otherwise");
    w.free();
    w = ask(&mux, "GET", "/who", "10.1.2.3", "X-Forwarded-For", "198.51.100.7, 10.9.9.9");
    check(str.find(w.body[0:], "198.51.100.7|") == 0, "through trusted proxies: the client");
    w.free();
    w = ask(&mux, "GET", "/who", "203.0.113.5", "X-Forwarded-For", "1.2.3.4");
    check(str.find(w.body[0:], "203.0.113.5|") == 0, "from an untrusted address X-Forwarded-For is ignored");
    w.free();
    w = ask(&mux, "GET", "/who", "::1", "X-Forwarded-For", "1.2.3.4, 198.51.100.8, 127.0.0.1");
    check(str.find(w.body[0:], "198.51.100.8|") == 0, "the right-most untrusted one wins (a client can't fake past it)");
    w.free();
    bool threw = false;
    try { middleware.realIp(&mux, "10.0.0.0/33"); } catch (Error e) { threw = e.code == http.ERR_HTTP; }
    check(threw, "a bad range throws");
    mux.free();

    http.Mux api;
    middleware.CorsOptions co;
    co.origins = "https://app.example.com, https://admin.example.com";
    co.expose = "X-Request-Id";
    co.credentials = true;
    middleware.cors(&api, &co);
    api.handle("GET /who", &who);
    w = ask(&api, "GET", "/who", "", "Origin", "https://app.example.com");
    check(w.code == 200 && hdr(&w, "Access-Control-Allow-Origin: https://app.example.com\r\n") &&
          hdr(&w, "Access-Control-Allow-Credentials: true\r\n") && hdr(&w, "Access-Control-Expose-Headers: X-Request-Id\r\n") &&
          hdr(&w, "Vary: Origin\r\n"), "an allowed origin");
    w.free();
    w = ask(&api, "OPTIONS", "/who", "", "Origin", "https://admin.example.com", "Access-Control-Request-Method", "PUT");
    check(w.code == 204 && hdr(&w, "Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, HEAD\r\n") &&
          hdr(&w, "Access-Control-Max-Age: 600\r\n"), "a preflight is answered before routing");
    w.free();
    w = ask(&api, "GET", "/who", "", "Origin", "https://evil.example");
    check(w.code == 200 && !hdr(&w, "Access-Control-Allow-Origin"), "another origin gets no CORS headers");
    w.free();
    w = ask(&api, "OPTIONS", "/who", "", "Origin", "https://evil.example", "Access-Control-Request-Method", "DELETE");
    check(w.code == 403, "its preflight is refused");
    w.free();
    api.free();

    middleware.rateLimit(&gLimited, 3, 60);
    gLimited.handle("GET /who", &who);
    i64 codes[5];
    for (i64 i = 0; i < 5; i++) {
        w = ask(&gLimited, "GET", "/who", "192.0.2.50", "", "");
        codes[i] = w.code;
        if (i == 3) {
            check(hdr(&w, "Retry-After: 20\r\n") && hdr(&w, "RateLimit-Limit: 3\r\n") && hdr(&w, "RateLimit-Remaining: 0\r\n"),
                  "429 says when to come back");
        }
        w.free();
    }
    check(codes[0] == 200 && codes[2] == 200 && codes[3] == 429 && codes[4] == 429, "3 a minute, then 429");
    w = ask(&gLimited, "GET", "/who", "192.0.2.51", "", "");
    check(w.code == 200, "another client has its own budget");
    w.free();
    gLimited.free();
    middleware.rateLimit(&gLimited, 50, 3600);
    gLimited.handle("GET /who", &who);
    void *t[4];
    for (i64 i = 0; i < 4; i++) { pthread_create(&t[i], null, &hammer, null); }
    for (i64 i = 0; i < 4; i++) { pthread_join(t[i], null); }
    check(gAllowed == 50, "4 threads at once: exactly the budget");
    gLimited.free();

    http.Mux app;
    http.Mux *priv = app.group("/me");
    middleware.bearer(priv, &check2);
    priv.handle("GET /{$}", &me);
    app.handle("GET /open", &tag);
    w = ask(&app, "GET", "/me/", "", "Authorization", "Bearer s3cret");
    check(w.code == 200 && str.equals(w.body[0:], "user 42"), "a good token: the handler has the user");
    w.free();
    w = ask(&app, "GET", "/me/", "", "Authorization", "Bearer nope");
    check(w.code == 401 && hdr(&w, "WWW-Authenticate: Bearer error=\"invalid_token\"\r\n"), "a bad token: 401");
    w.free();
    w = ask(&app, "GET", "/me/", "", "", "");
    check(w.code == 401 && hdr(&w, "WWW-Authenticate: Bearer\r\n"), "no token: 401");
    w.free();
    w = ask(&app, "GET", "/open", "", "", "");
    check(w.code == 200, "routes outside the group are open");
    w.free();
    app.free();

    http.Mux m2;
    i64 seven = 7;
    m2.useWith(&ctxRun, &seven);
    m2.handle("GET /t", &tag);
    w = ask(&m2, "GET", "/t", "", "", "");
    check(str.equals(w.body[0:], "7"), "mux.useWith passes its context");
    w.free();
    m2.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
