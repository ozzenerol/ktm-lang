import cstr, fs, libc, log, net, net.http, str, test;

/* net.http middleware (v0.19.0): mux.use runs in order before every
 * request (404 and 405 too) and can stop it; mux.group(prefix) puts a
 * prefix in front of its patterns and has middlewares of its own, for
 * its routes only, nested groups too; mux.files(prefix, dir) serves
 * static files (index.html, /dir -> /dir/, no ".." even escaped, content
 * types, Last-Modified + If-Modified-Since 304); Options.accessLog logs
 * a line per request through a real server. */

extern "c" i32 pthread_create(void *t, void *attr, void *start, void *arg);
extern "c" i32 pthread_join(void *t, void **ret);
extern "c" i32 usleep(u32 us);

#define ROOT "./ktm-754"
#define LOGF "./ktm-754.log"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s", what); }
}

/* what ran, in order */
u8 gTrace[64];
i64 gTraceLen = 0;
void trace(u8 c) { if (gTraceLen < 63) { gTrace[gTraceLen++] = c; gTrace[gTraceLen] = 0; } }

bool mw1(http.Writer *w, http.Request *r) { trace('1'); return true; }
bool mw2(http.Writer *w, http.Request *r) { trace('2'); return true; }
bool mwApi(http.Writer *w, http.Request *r) { trace('a'); return true; }
bool mwV1(http.Writer *w, http.Request *r) { trace('v'); return true; }
/* stops a request without "Authorization: yes" */
bool auth(http.Writer *w, http.Request *r) {
    trace('x');
    if (str.equals(r.header("Authorization"), "yes")) { return true; }
    w.error(401, "unauthorized");
    return false;
}
void h(http.Writer *w, http.Request *r) { trace('h'); w.text(200, "id=%s", r.param("id")); }

i32 gCode = 0;
u8 gBody[512];
u8 gType[128];
u8 gLoc[128];
u8 gAllow[128];
u8 gLastMod[128];

void copyTo(u8 *dst, u8[] s, i64 cap) {
    i64 n = s.len < cap - 1 ? s.len : cap - 1;
    for (i64 i = 0; i < n; i++) { dst[i] = s[i]; }
    dst[n] = 0;
}

/* serves method target (one header name: value, if name isn't "") */
void serveH(http.Mux *mux, u8[] method, u8[] target, u8[] hname, u8[] hvalue) {
    gTraceLen = 0;
    gTrace[0] = 0;
    http.Request r = http.testRequest(method, target);
    if (hname.len) { r.addHeader(hname, hvalue); }
    http.Writer w = http.recorder();
    mux.serve(&w, &r);
    gCode = w.code ? w.code : 200;
    copyTo(gBody, w.body, sizeof(gBody));
    copyTo(gType, w.getHeader("Content-Type"), sizeof(gType));
    copyTo(gLoc, w.getHeader("Location"), sizeof(gLoc));
    copyTo(gAllow, w.getHeader("Allow"), sizeof(gAllow));
    copyTo(gLastMod, w.getHeader("Last-Modified"), sizeof(gLastMod));
    w.free();
    r.free();
}
void serve(http.Mux *mux, u8[] method, u8[] target) { serveH(mux, method, target, "", ""); }

bool traced(u8 *want) { return str.equals(cstr.view(gTrace), cstr.view(want)); }
bool body(u8 *want) { return str.equals(cstr.view(gBody), cstr.view(want)); }

bool throws(http.Mux *mux, u8[] pattern) {
    try {
        mux.handle(pattern, &h);
    } catch (Error e) {
        return e.code == http.ERR_PATTERN;
    }
    return false;
}

void middleware() {
    http.Mux mux;
    mux.use(&mw1);
    mux.use(&mw2);
    mux.handle("GET /users/{id}", &h);
    serve(&mux, "GET", "/users/7");
    check(gCode == 200 && body("id=7") && traced("12h"), "middlewares in order, then the handler");
    serve(&mux, "GET", "/nope");
    check(gCode == 404 && traced("12"), "middlewares run for a 404");
    serve(&mux, "POST", "/users/7");
    check(gCode == 405 && traced("12"), "and for a 405");
    mux.use(&auth);
    serve(&mux, "GET", "/users/7");
    check(gCode == 401 && traced("12x") && str.contains(cstr.view(gBody), "unauthorized"), "false stops: its reply, no handler");
    serveH(&mux, "GET", "/users/7", "Authorization", "yes");
    check(gCode == 200 && traced("12xh"), "true goes on");
    serve(&mux, "GET", "/nope");
    check(gCode == 401 && traced("12x"), "a stopping middleware wins over the 404");
    bool threw = false;
    try { mux.use(null); } catch (Error e) { threw = e.code == http.ERR_PATTERN; }
    check(threw, "use(null) throws");
    mux.free();
    serve(&mux, "GET", "/users/7");
    check(gCode == 404 && traced(""), "free: no routes, no middlewares");
}

void groups() {
    http.Mux mux;
    mux.use(&mw1);
    mux.handle("GET /users/{id}", &h);
    http.Mux *api = mux.group("/api");
    api.use(&mwApi);
    api.handle("GET /users/{id}", &h);
    api.handle("/", &h);
    http.Mux *v1 = api.group("/v1/");
    v1.use(&mwV1);
    v1.handle("GET /items/{id}", &h);
    http.Mux *admin = mux.group("/admin");
    admin.use(&auth);
    admin.handle("POST /reset", &h);

    serve(&mux, "GET", "/users/1");
    check(gCode == 200 && traced("1h"), "a top route: only the top middlewares");
    serve(&mux, "GET", "/api/users/2");
    check(gCode == 200 && body("id=2") && traced("1ah"), "a group route: top, then the group's");
    serve(&mux, "GET", "/api/v1/items/3");
    check(gCode == 200 && body("id=3") && traced("1avh"), "a nested group: outermost first");
    serve(&mux, "GET", "/api/other/x");
    check(gCode == 200 && traced("1ah"), "the group's \"/\" is the subtree /api/");
    serve(&mux, "GET", "/api");
    check(gCode == 301 && str.equals(cstr.view(gLoc), "/api/"), "and /api redirects to it");
    serve(&mux, "GET", "/api/v1/nope");
    check(gCode == 200 && traced("1ah"), "/api/v1/nope falls to /api/");
    serve(&mux, "POST", "/admin/reset");
    check(gCode == 401 && traced("1x"), "a group's stopping middleware");
    serve(&mux, "GET", "/admin/reset");
    check(gCode == 405 && traced("1") && str.equals(cstr.view(gAllow), "POST"), "a 405 runs only the top middlewares");
    serve(&mux, "GET", "/admin/nope");
    check(gCode == 404 && traced("1"), "a 404 under a group: only the top middlewares");

    gTraceLen = 0;
    http.Request r = http.testRequest("GET", "/api/v1/items/4");
    http.Writer w = http.recorder();
    v1.serve(&w, &r);
    check(w.code == 200 && traced("1avh"), "serve on a group serves through the top Mux");
    w.free();
    r.free();

    check(throws(api, "GET /users/{x}"), "a group's route conflicting with its own");
    check(throws(v1, "GET /items/{x}"), "a nested group's conflict");
    check(!throws(v1, "PUT /items/{id}"), "no conflict across methods");
    bool threw = false;
    try { mux.group("api"); } catch (Error e) { threw = e.code == http.ERR_PATTERN; }
    check(threw, "a prefix without a leading / throws");
    http.Mux *all = mux.group("/");
    all.handle("GET /health", &h);
    serve(&mux, "GET", "/health");
    check(gCode == 200 && traced("1h"), "group(\"/\"): no prefix");
    api.free();
    serve(&mux, "GET", "/api/users/5");
    check(gCode == 200 && traced("1ah"), "free() on a group does nothing");
    mux.free();
}

void writeFile(u8 *path, u8 *text) {
    check(fs.writeAll(cstr.view(path), cstr.view(text)), "write a fixture");
}

void files() {
    fs.remove(ROOT, fs.REMOVE_RECURSE | fs.REMOVE_FORCE);
    fs.remove(ROOT "-secret", fs.REMOVE_FORCE);
    fs.makeDirAll(ROOT "/sub");
    fs.makeDirAll(ROOT "/empty");
    writeFile(ROOT "/index.html", "<h1>home</h1>");
    writeFile(ROOT "/site.CSS", "body{}");
    writeFile(ROOT "/a b.txt", "spaced");
    writeFile(ROOT "/data.bin", "\x01\x02");
    writeFile(ROOT "/sub/index.html", "sub home");
    writeFile(ROOT "/sub/b.json", "{}");
    writeFile(ROOT "-secret", "secret");

    http.Mux mux;
    mux.files("/static/", ROOT "/");
    http.Mux *api = mux.group("/api");
    api.use(&mwApi);
    api.files("/assets/", ROOT);

    serve(&mux, "GET", "/static/site.CSS");
    check(gCode == 200 && body("body{}") && str.equals(cstr.view(gType), "text/css; charset=utf-8"), "a file, its content type");
    check(str.endsWith(cstr.view(gLastMod), " GMT"), "Last-Modified");
    serve(&mux, "GET", "/static/a%20b.txt");
    check(gCode == 200 && body("spaced") && str.startsWith(cstr.view(gType), "text/plain"), "an escaped name");
    serve(&mux, "GET", "/static/data.bin");
    check(gCode == 200 && str.equals(cstr.view(gType), "application/octet-stream"), "an unknown extension");
    serve(&mux, "GET", "/static/");
    check(gCode == 200 && body("<h1>home</h1>") && str.startsWith(cstr.view(gType), "text/html"), "the directory: index.html");
    serve(&mux, "GET", "/static");
    check(gCode == 301 && str.equals(cstr.view(gLoc), "/static/"), "/static -> /static/");
    serve(&mux, "GET", "/static/sub?x=1");
    check(gCode == 301 && str.equals(cstr.view(gLoc), "/static/sub/?x=1"), "a directory without / -> with /, query kept");
    serve(&mux, "GET", "/static/sub/");
    check(gCode == 200 && body("sub home"), "a subdirectory's index.html");
    serve(&mux, "GET", "/static/sub/b.json");
    check(gCode == 200 && str.equals(cstr.view(gType), "application/json"), "a file in a subdirectory");
    serve(&mux, "GET", "/static/empty/");
    check(gCode == 404, "a directory without index.html: 404, no listing");
    serve(&mux, "GET", "/static/nope.txt");
    check(gCode == 404, "a missing file: 404");
    serve(&mux, "GET", "/static/site.CSS/");
    check(gCode == 404, "a file with a trailing slash: 404");
    serve(&mux, "GET", "/static/%2e%2e/ktm-754-secret");
    check(gCode == 400 && !str.contains(cstr.view(gBody), "secret"), "an escaped .. is refused");
    serve(&mux, "GET", "/static/sub/%2E%2E/%2e%2e/ktm-754-secret");
    check(gCode == 400, "an escaped .. deeper down too");
    serve(&mux, "GET", "/static/../ktm-754-secret");
    check(gCode == 301 && str.equals(cstr.view(gLoc), "/ktm-754-secret"), "a raw .. is cleaned by the mux, not served");
    serve(&mux, "GET", "/static/a%00b");
    check(gCode == 400, "a NUL is refused");
    serve(&mux, "HEAD", "/static/site.CSS");
    check(gCode == 200, "HEAD");
    serve(&mux, "POST", "/static/site.CSS");
    check(gCode == 405 && str.equals(cstr.view(gAllow), "GET, HEAD"), "POST: 405, Allow GET, HEAD");

    serve(&mux, "GET", "/static/site.CSS");
    u8 lm[128];
    copyTo(lm, cstr.view(gLastMod), sizeof(lm));
    serveH(&mux, "GET", "/static/site.CSS", "If-Modified-Since", cstr.view(lm));
    check(gCode == 304 && body(""), "If-Modified-Since the Last-Modified: 304");
    serveH(&mux, "GET", "/static/site.CSS", "If-Modified-Since", "Mon, 01 Jan 2001 00:00:00 GMT");
    check(gCode == 200 && body("body{}"), "an older If-Modified-Since: 200");
    serveH(&mux, "GET", "/static/site.CSS", "If-Modified-Since", "yesterday");
    check(gCode == 200, "a bad If-Modified-Since is ignored");

    serve(&mux, "GET", "/api/assets/sub/b.json");
    check(gCode == 200 && body("{}") && traced("a"), "files in a group, with its middleware");

    bool threw = false;
    try { mux.files("/static", ROOT); } catch (Error e) { threw = e.code == http.ERR_PATTERN; }
    check(threw, "a files prefix without a trailing / throws");
    threw = false;
    try { mux.files("/static/", ROOT); } catch (Error e) { threw = e.code == http.ERR_PATTERN; }
    check(threw, "the same files prefix twice conflicts");

    check(str.equals(http.contentType("x/LOGO.PNG"), "image/png"), "contentType: case ignored");
    check(str.equals(http.contentType("a.dir/noext"), "application/octet-stream"), "contentType: a dot in a directory");
    check(str.equals(http.contentType("app.js"), "text/javascript; charset=utf-8"), "contentType: js");
    mux.free();
    fs.remove(ROOT, fs.REMOVE_RECURSE);
    fs.remove(ROOT "-secret");
}

/* ---- the access log, through a real server ---- */

i32 gPort;

i32 freePort() {
    i32 fd = net.listen(0, "127.0.0.1", 1);
    i32 port = net.localPort(fd);
    libc.close(fd);
    return port;
}

void ask(u8[] req) {
    i32 fd = -1;
    for (i64 i = 0; i < 200 && fd < 0; i++) {
        fd = net.dial("127.0.0.1", gPort);
        if (fd < 0) { usleep(10000); }
    }
    if (fd < 0) { return; }
    net.setRecvTimeout(fd, 5000);
    net.send(fd, req);
    u8 buf[4096];
    while (libc.recv(fd, buf, sizeof(buf), 0) > 0) { }
    libc.close(fd);
}

void *drive(void *unused) {
    ask("GET /api/users/7?x=1 HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n");
    ask("DELETE /api/users/7 HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n");
    ask("HEAD /api/users/8 HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n");
    http.shutdown();
    return null;
}

void accessLog() {
    fs.remove(LOGF, fs.REMOVE_FORCE);
    log.Options lo = log.options();
    lo.path = LOGF;
    log.setup(&lo);
    http.Mux mux;
    http.Mux *api = mux.group("/api");
    api.handle("GET /users/{id}", &h);
    http.Options o;
    o.threads = 1;
    o.accessLog = true;
    o.noSignals = true;
    gPort = freePort();
    void *t;
    pthread_create(&t, null, &drive, null);
    i32 rc = http.listen(gPort, &mux, null, &o);
    pthread_join(t, null);
    log.shutdown();
    check(rc == 0, "listen returned 0");
    String text = fs.readAll(LOGF);
    u8[] all = text.ptr ? text.ptr[0:text.len] : "";
    check(str.contains(all, "GET /api/users/7?x=1 200 4 "), "a line: method target status bytes");
    check(str.contains(all, "DELETE /api/users/7 405 "), "a 405 is logged");
    check(str.contains(all, "HEAD /api/users/8 200 0 "), "HEAD: no body bytes");
    check(str.contains(all, "ms"), "with the time");
    if (gCorrect != gTests) { printf("\n  log: %s", all); }
    text.free();
    fs.remove(LOGF);
}

i32 main()
{
    printf("Test - http middleware, groups, files, access log: ");
    middleware();
    groups();
    files();
    accessLog();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
