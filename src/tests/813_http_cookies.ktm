import net.http, str, test;

/* net.http (v0.21.0): cookies. r.cookie over several Cookie headers,
 * quoted values, spaces, a missing one; w.setCookie with every attribute,
 * two cookies in one reply, deleteCookie, and the names and values a
 * browser would refuse. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

/* The reply's headers as text, for finding Set-Cookie lines */
bool hasLine(http.Writer *w, u8[] line) {
    return str.find(w.headers[0:], line) >= 0;
}

bool refused(u8[] name, u8[] value, u8[] path) {
    http.Writer w = http.recorder();
    http.Cookie c;
    c.name = name;
    c.value = value;
    c.path = path;
    bool threw = false;
    try { w.setCookie(&c); } catch (Error e) { threw = e.code == http.ERR_HTTP; }
    w.free();
    return threw;
}

i32 main() {
    printf("Test - http cookies: ");
    http.Request r = http.testRequest("GET", "/");
    r.addHeader("Cookie", "theme=dark; session=abc123 ;  lang=\"it\"");
    r.addHeader("Cookie", "late=1; empty=");
    check(str.equals(r.cookie("session"), "abc123") && str.equals(r.cookie("theme"), "dark") &&
          str.equals(r.cookie("lang"), "it") && str.equals(r.cookie("late"), "1"), "reading cookies");
    check(r.hasCookie("empty") && str.equals(r.cookie("empty"), "") && !r.hasCookie("nope") &&
          str.equals(r.cookie("nope"), "") && !r.hasCookie("sess"), "an empty cookie, a missing one");
    r.free();

    http.Writer w = http.recorder();
    http.Cookie c;
    c.name = "session";
    c.value = "tok_123-x.y";
    c.maxAge = 3600;
    c.httpOnly = true;
    c.secure = true;
    c.sameSite = http.SameSite.lax;
    w.setCookie(&c);
    http.Cookie d;
    d.name = "theme";
    d.value = "dark";
    d.path = "/app";
    d.domain = "example.com";
    d.expires = 1700000000;
    d.sameSite = http.SameSite.none;
    w.setCookie(&d);
    check(hasLine(&w, "Set-Cookie: session=tok_123-x.y; Path=/; Max-Age=3600; HttpOnly; Secure; SameSite=Lax\r\n"),
          "every attribute");
    check(hasLine(&w, "Set-Cookie: theme=dark; Path=/app; Domain=example.com; Expires=Tue, 14 Nov 2023 22:13:20 GMT; "
                      "Secure; SameSite=None\r\n"), "a second cookie: Expires, SameSite=None adds Secure");
    w.deleteCookie("old");
    check(hasLine(&w, "Set-Cookie: old=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT\r\n"), "deleteCookie");
    w.free();

    check(refused("", "x", "") && refused("a b", "x", "") && refused("a;b", "x", "") && refused("a=b", "x", "") &&
          refused("ok", "has space", "") && refused("ok", "a;b", "") && refused("ok", "q\"", "") &&
          refused("ok", "a,b", "") && refused("ok", "x", "/a;b") && !refused("ok", "", ""), "names and values a browser refuses");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
