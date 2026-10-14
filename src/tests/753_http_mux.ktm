import cstr, net.http, str, test;

/* http.Mux routes like Go 1.22's ServeMux: "[METHOD ]/path" patterns
 * with {name}, {rest...}, {$} and trailing-slash subtrees; the most
 * specific pattern wins whatever the order they were added in;
 * conflicting and duplicate patterns throw at handle(); 404, 405 with
 * Allow; GET serves HEAD; segments are matched and params given
 * unescaped; "/dir" redirects to "/dir/" and unclean paths to clean
 * ones (301, query kept). Through a real Request and Writer. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s", what); }
}

u8 *gHit = "";
void hA(http.Writer *w, http.Request *r) { gHit = "A"; w.text(200, "A"); }
void hB(http.Writer *w, http.Request *r) { gHit = "B"; w.text(200, "B"); }
void hC(http.Writer *w, http.Request *r) { gHit = "C"; w.text(200, "C"); }
void hD(http.Writer *w, http.Request *r) { gHit = "D"; w.text(200, "D"); }
void hE(http.Writer *w, http.Request *r) { gHit = "E"; w.text(200, "E"); }
/* echoes params */
void hP(http.Writer *w, http.Request *r) {
    gHit = "P";
    w.text(200, "%s|%s|%s", r.param("a"), r.param("b"), r.param("rest"));
}

/* the last reply */
i32 gCode = 0;
u8 gBody[256];
u8 gLoc[256];
u8 gAllow[256];

void copyTo(u8 *dst, u8[] s) {
    i64 n = s.len < 255 ? s.len : 255;
    for (i64 i = 0; i < n; i++) { dst[i] = s[i]; }
    dst[n] = 0;
}

/* serves method target, returns the handler hit ("" if none) */
u8 *serve(http.Mux *mux, u8[] method, u8[] target) {
    gHit = "";
    http.Request r = http.testRequest(method, target);
    http.Writer w = http.recorder();
    mux.serve(&w, &r);
    gCode = w.code ? w.code : 200;
    copyTo(gBody, w.body);
    copyTo(gLoc, w.getHeader("Location"));
    copyTo(gAllow, w.getHeader("Allow"));
    w.free();
    r.free();
    return gHit;
}

bool hits(http.Mux *mux, u8[] method, u8[] target, u8 *want) {
    return str.equals(cstr.view(serve(mux, method, target)), cstr.view(want));
}

/* handle() throws ERR_PATTERN with both patterns in the message */
bool refused(http.Mux *mux, u8[] pattern, u8[] other) {
    try {
        mux.handle(pattern, &hE);
    } catch (Error e) {
        u8[] m = cstr.view(e.message);
        if (e.code != http.ERR_PATTERN) { printf("\n  code 0x%x", e.code); return false; }
        if (!str.contains(m, pattern) || !str.contains(m, other)) {
            printf("\n  message: %s", e.message);
            return false;
        }
        return true;
    }
    return false;
}

bool bad(u8[] pattern) {
    http.Mux mux;
    bool threw = false;
    try {
        mux.handle(pattern, &hA);
    } catch (Error e) {
        threw = e.code == http.ERR_PATTERN;
    }
    mux.free();
    return threw;
}

void basics() {
    http.Mux mux;      /* zeroed, no init */
    check(hits(&mux, "GET", "/", "") && gCode == 404, "an empty mux: 404");
    mux.handle("GET /users/{a}", &hP);
    mux.handle("/health", &hA);
    mux.handle("POST /users", &hB);
    mux.handle("DELETE /users/{b}", &hC);
    mux.handle("/files/{rest...}", &hP);

    check(hits(&mux, "GET", "/users/7", "P") && str.equals(cstr.view(gBody), "7||"), "{a} param");
    check(hits(&mux, "GET", "/users/a%20b%2Fc", "P") && str.equals(cstr.view(gBody), "a b/c||"), "params unescaped");
    check(hits(&mux, "GET", "/users/%zz", "P") && str.equals(cstr.view(gBody), "%zz||"), "a bad escape: the raw segment");
    check(hits(&mux, "GET", "/users/", "") && gCode == 404, "{a} doesn't match the trailing slash");
    check(hits(&mux, "GET", "/users/7/x", "") && gCode == 404, "{a} is one segment");
    check(hits(&mux, "PUT", "/health", "A") && hits(&mux, "GET", "/health?x=1", "A"), "no method: every method");
    check(hits(&mux, "GET", "/health/", "") && gCode == 404, "/health doesn't match /health/");
    check(hits(&mux, "HEAD", "/users/1", "P"), "GET serves HEAD");
    check(hits(&mux, "POST", "/users", "B"), "POST /users");
    check(hits(&mux, "DELETE", "/users/9", "C"), "DELETE /users/{b}");
    check(hits(&mux, "GET", "/files/a/b%20c/", "P") && str.equals(cstr.view(gBody), "||a/b c/"), "{rest...}: the rest, unescaped");
    check(hits(&mux, "GET", "/files/", "P") && str.equals(cstr.view(gBody), "||"), "{rest...} may be empty");
    check(hits(&mux, "GET", "/nope", "") && gCode == 404 && str.contains(cstr.view(gBody), "not found"), "404");

    check(hits(&mux, "PUT", "/users/7", "") && gCode == 405, "405");
    check(str.equals(cstr.view(gAllow), "DELETE, GET, HEAD"), "Allow: sorted, HEAD with GET");
    check(str.contains(cstr.view(gBody), "method not allowed"), "405 body");
    check(hits(&mux, "GET", "/users", "") && gCode == 405 && str.equals(cstr.view(gAllow), "POST"), "405 Allow: POST");
    mux.free();
    check(hits(&mux, "GET", "/health", "") && gCode == 404, "after free: empty");
}

/* the same routes added in two orders route the same */
void precedence(bool reverse) {
    http.Mux mux;
    u8[][] pats = {"/", "/users/{a}", "GET /users/me", "GET /users/{a}", "/static/", "/static/css/{a}", "/{$}",
                   "/users/{a}/{rest...}"};
    fn(http.Writer *w, http.Request *r) void hs[8] = {&hA, &hB, &hC, &hD, &hE, &hP, &hC, &hP};
    for (i64 i = 0; i < pats.len; i++) {
        i64 k = reverse ? pats.len - 1 - i : i;
        mux.handle(pats[k], hs[k]);
    }
    u8 *tag = reverse ? " (added in reverse)" : "";
    check(hits(&mux, "GET", "/users/me", "C"), "GET /users/me beats GET /users/{a}");
    check(hits(&mux, "POST", "/users/me", "B"), "/users/{a} for POST /users/me");
    check(hits(&mux, "GET", "/users/7", "D"), "GET /users/{a} beats /users/{a}");
    check(hits(&mux, "POST", "/users/7", "B"), "/users/{a} for other methods");
    check(hits(&mux, "HEAD", "/users/7", "D"), "GET /users/{a} takes HEAD");
    check(hits(&mux, "GET", "/users/7/x/y", "P"), "{rest...} after {a}");
    check(hits(&mux, "GET", "/static/css/x", "P") && str.equals(cstr.view(gBody), "x||"), "a deeper pattern beats the subtree");
    check(hits(&mux, "GET", "/static/js/x", "E"), "the subtree /static/");
    check(hits(&mux, "GET", "/", "C"), "/{$} beats / for /");
    check(hits(&mux, "GET", "/other", "A"), "/ catches the rest");
    if (gCorrect != gTests) { printf("\n  %s", tag); }
    mux.free();
}

/* second is refused after first */
bool conflict(u8[] first, u8[] second) {
    http.Mux mux;
    mux.handle(first, &hA);
    bool ok = refused(&mux, second, first);
    mux.free();
    return ok;
}

void conflicts() {
    check(conflict("/a/{x}", "/a/{x}"), "a duplicate");
    check(conflict("/a/{x}", "/a/{y}"), "same requests, other names");
    check(conflict("/a/{x}", "/{y}/b"), "/{y}/b vs /a/{x}: neither more specific");
    check(conflict("GET /", "/x"), "/x vs GET /: more methods, more specific path");
    check(conflict("/x", "GET /"), "the same the other way round");
    check(conflict("/a/{x}", "GET /"), "GET / vs /a/{x}");
    check(conflict("GET /u/{x}", "/u/me"), "/u/me vs GET /u/{x}");
    check(conflict("GET /u/me", "HEAD /u/{x}"), "HEAD /u/{x} vs GET /u/me");
    check(conflict("/b/", "/b/{rest...}"), "/b/{rest...} is /b/");
    check(conflict("GET /c", "GET  /c"), "spaces after the method don't make a new pattern");

    http.Mux mux;
    mux.handle("GET /c", &hA);
    mux.handle("/a/{x}", &hD);
    check(refused(&mux, "/a/{y}", "/a/{x}"), "refused on a mux with routes");
    mux.handle("HEAD /c", &hB);          /* fine: HEAD is more specific than GET */
    mux.handle("POST /c", &hC);          /* fine: disjoint methods */
    mux.handle("/a/b", &hE);             /* fine: more specific */
    check(hits(&mux, "HEAD", "/c", "B") && hits(&mux, "GET", "/c", "A") && hits(&mux, "POST", "/c", "C"), "the routes around a refused one work");
    check(hits(&mux, "GET", "/a/b", "E") && hits(&mux, "GET", "/a/z", "D"), "and so does the one it conflicted with");
    mux.free();

    check(bad(""), "empty pattern");
    check(bad("GET"), "no path");
    check(bad("example.com/x"), "a host");
    check(bad("users"), "no leading /");
    check(bad("/a/{x"), "an unclosed {");
    check(bad("/a/x{y}"), "{ inside a segment");
    check(bad("/{rest...}/a"), "{...} not last");
    check(bad("/{$}/a"), "{$} not last");
    check(bad("/{x}/{x}"), "a duplicate name");
    check(bad("/{1x}"), "a bad name");
    check(bad("/{}"), "an empty name");
    check(bad("G(T /a"), "a bad method");
    check(!bad("/{a_1}/{B}/{rest...}") && !bad("/a%2Fb/{$}") && !bad("PATCH\t/x"), "good patterns");
}

void redirects() {
    http.Mux mux;
    mux.handle("/dir/", &hA);
    mux.handle("/files/{rest...}", &hB);
    mux.handle("/both/", &hC);
    mux.handle("/both", &hD);
    mux.handle("GET /get/", &hE);
    mux.handle("/", &hP);
    mux.handle("/a%2Fb/{x}", &hA);

    check(hits(&mux, "GET", "/dir", "") && gCode == 301 && str.equals(cstr.view(gLoc), "/dir/"), "/dir -> /dir/");
    check(hits(&mux, "GET", "/dir?q=1&r=2", "") && gCode == 301 && str.equals(cstr.view(gLoc), "/dir/?q=1&r=2"), "the query is kept");
    check(hits(&mux, "GET", "/files", "") && gCode == 301 && str.equals(cstr.view(gLoc), "/files/"), "{rest...} redirects too");
    check(hits(&mux, "GET", "/dir/", "A") && hits(&mux, "GET", "/dir/x/y", "A"), "the subtree");
    check(hits(&mux, "GET", "/both", "D") && hits(&mux, "GET", "/both/", "C"), "no redirect when /both is a route");
    check(hits(&mux, "POST", "/get", "P"), "no redirect for a method the subtree doesn't take");
    check(hits(&mux, "GET", "/get", "") && gCode == 301, "the redirect takes the method into account");
    check(hits(&mux, "GET", "/dir/x", "A"), "no redirect when the subtree matches deeper");

    check(hits(&mux, "GET", "/dir//x", "") && gCode == 301 && str.equals(cstr.view(gLoc), "/dir/x"), "// cleaned");
    check(hits(&mux, "GET", "/x/../dir/./y/?z=1", "") && gCode == 301 && str.equals(cstr.view(gLoc), "/dir/y/?z=1"), "/../ and /./ cleaned, query kept");
    check(hits(&mux, "GET", "/dir/..", "") && gCode == 301 && str.equals(cstr.view(gLoc), "/"), "/dir/.. -> /");
    check(hits(&mux, "GET", "/x/../dir", "") && gCode == 301 && str.equals(cstr.view(gLoc), "/dir/"), "clean, then /dir/");
    check(hits(&mux, "GET", "/a/.b/..c", "P"), "dot names aren't cleaned");

    check(hits(&mux, "GET", "/a%2fb/c%2Fd", "A"), "an escaped literal segment matches");
    check(hits(&mux, "GET", "/a/b/c", "P"), "a raw / isn't an escaped one");
    mux.free();
}

i32 main()
{
    printf("Test - http.Mux routing: ");
    basics();
    precedence(false);
    precedence(true);
    conflicts();
    redirects();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
