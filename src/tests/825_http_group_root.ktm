import net.http, str, test;

/* net.http (v0.21.0): a group's route for exactly its prefix. In group
 * "/bookmarks", handle("GET", h) and handle("POST", h) are GET and POST
 * /bookmarks, with the group's middleware; handle("", h) takes any method;
 * "/bookmarks/" stays separate; nested groups too. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

bool guard(http.Writer *w, http.Request *r) {
    if (r.hasHeader("Authorization")) { return true; }
    w.error(401, "unauthorized");
    return false;
}

void list(http.Writer *w, http.Request *r) { w.text(200, "list"); }
void create(http.Writer *w, http.Request *r) { w.text(201, "create"); }
void one(http.Writer *w, http.Request *r) { w.text(200, "one %s", r.param("id")); }
void any(http.Writer *w, http.Request *r) { w.text(200, "any %s", r.method); }
void open(http.Writer *w, http.Request *r) { w.text(200, "open"); }

http.Writer ask(http.Mux *m, u8[] method, u8[] target, bool auth) {
    http.Request r = http.testRequest(method, target);
    if (auth) { r.addHeader("Authorization", "Bearer x"); }
    http.Writer w = http.recorder();
    m.serve(&w, &r);
    r.free();
    return w;
}

bool is(http.Mux *m, u8[] method, u8[] target, bool auth, i32 code, u8[] body) {
    http.Writer w = ask(m, method, target, auth);
    bool ok = w.code == code && (!body.len || str.equals(w.body[0:], body));
    if (!ok) { printf("\n  %.*s %.*s: %d %.*s", method.len, method.ptr, target.len, target.ptr, w.code, w.body.len, w.body.ptr); }
    w.free();
    return ok;
}

i32 main() {
    printf("Test - http group root: ");
    http.Mux mux;
    mux.handle("GET /health", &open);
    http.Mux *b = mux.group("/bookmarks");
    b.use(&guard);
    b.handle("GET", &list);
    b.handle("POST ", &create);
    b.handle("GET /{id}", &one);
    http.Mux *x = mux.group("/x/");
    x.handle("", &any);
    http.Mux *y = b.group("/archive");
    y.handle("GET", &list);

    check(is(&mux, "GET", "/bookmarks", true, 200, "list") && is(&mux, "POST", "/bookmarks", true, 201, "create"),
          "GET and POST on exactly the prefix");
    check(is(&mux, "GET", "/bookmarks", false, 401, ""), "with the group's middleware");
    check(is(&mux, "GET", "/bookmarks/7", true, 200, "one 7") && is(&mux, "DELETE", "/bookmarks", true, 405, ""),
          "the group's other routes; other methods 405");
    check(is(&mux, "GET", "/bookmarks/", true, 404, ""), "the prefix with a slash is another path");
    check(is(&mux, "PUT", "/x", false, 200, "any PUT") && is(&mux, "GET", "/x", false, 200, "any GET"),
          "\"\" takes any method (a prefix with a trailing slash too)");
    check(is(&mux, "GET", "/bookmarks/archive", true, 200, "list") && is(&mux, "GET", "/bookmarks/archive", false, 401, ""),
          "a nested group");
    check(is(&mux, "GET", "/health", false, 200, "open"), "routes outside are untouched");
    mux.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
