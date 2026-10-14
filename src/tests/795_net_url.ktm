import net.url, str, test;

/* net.url (v0.20.0): parsing into parts, building back, IPv6 hosts,
 * ports, a user and password, references without a scheme, what parse
 * refuses, query values, and RFC 3986 section 5.4's reference resolution
 * examples (all of the normal ones and the abnormal ones). */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

bool same(String *s, u8[] want) { return str.equals((*s)[0:], want); }

/* parse, then string() gives text back unchanged */
bool roundTrip(u8[] text) {
    url.URL u;
    bool ok = url.parse(text, &u);
    String s = u.string();
    ok = ok && str.equals(s[0:], text);
    if (!ok) { printf("\n  round trip of %.*s gave %.*s\n", text.len, text.ptr, s.len, s.ptr); }
    s.free();
    u.free();
    return ok;
}

url.URL gBase;
i64 gResolveBad = 0;

void resolves(u8[] ref, u8[] want) {
    url.URL r;
    bool ok = gBase.resolve(ref, &r);
    String s = r.string();
    if (!ok || !str.equals(s[0:], want)) {
        gResolveBad++;
        printf("\n  resolve %.*s: got %.*s, want %.*s", ref.len, ref.ptr, s.len, s.ptr, want.len, want.ptr);
    }
    s.free();
    r.free();
}

i32 main() {
    printf("Test - net.url: ");
    url.URL u;
    check(url.parse("HTTPS://ann:p%40ss@Example.COM:8443/a%20b/c?x=1&y=two#top", &u), "parse");
    check(same(&u.scheme, "https") && same(&u.user, "ann") && same(&u.password, "p@ss") && u.hasPassword &&
          same(&u.host, "example.com") && u.port == 8443 && same(&u.path, "/a%20b/c") &&
          same(&u.rawQuery, "x=1&y=two") && same(&u.fragment, "top"), "its parts");
    check(u.portOrDefault() == 8443, "the port");
    u.free();
    check(url.parse("http://[::1]:8080/x", &u) && same(&u.host, "::1") && u.port == 8080, "an IPv6 host");
    String s = u.string();
    check(same(&s, "http://[::1]:8080/x"), "IPv6 back in brackets");
    s.free();
    check(url.parse("postgres://db.internal/app", &u) && u.port == 0 && u.portOrDefault() == 5432, "a scheme's port");
    check(url.parse("/just/a/path?q=1", &u) && u.scheme.len == 0 && u.host.len == 0 && same(&u.path, "/just/a/path") &&
          same(&u.rawQuery, "q=1"), "a reference without scheme or host");
    check(url.parse("mailto:ann@example.com", &u) && same(&u.scheme, "mailto") && same(&u.path, "ann@example.com"),
          "a scheme with no //");
    check(roundTrip("https://example.com") && roundTrip("https://example.com/") && roundTrip("http://a/b?") &&
          roundTrip("http://a/b#") && roundTrip("//host/p") && roundTrip("a/b") && roundTrip("http://u@h/") &&
          roundTrip("http://u:@h/") && roundTrip("file:///etc/hosts"), "string() gives the text back");
    check(!url.parse("http://h:99999/", &u) && !url.parse("http://h:8x/", &u) && !url.parse("http://[::1/", &u) &&
          !url.parse("http://h/a b", &u) && !url.parse("http://h/%zz", &u) && !url.parse("http://h/%2", &u) &&
          u.host.len == 0, "bad URLs are refused");
    u.free();

    url.Values v;
    check(url.parseQuery("a=1&b=two+words&c&a=3&d=%26", &v) && str.equals(v.get("a"), "1") &&
          str.equals(v.get("b"), "two words") && v.has("c") && str.equals(v.get("c"), "") &&
          str.equals(v.get("d"), "&") && v.items.len == 5 && !v.has("zz"), "query values");
    v.add("e", "x y/z");
    String q = v.encode();
    check(same(&q, "a=1&b=two%20words&c=&a=3&d=%26&e=x%20y%2Fz"), "encode");
    q.free();
    check(!url.parseQuery("a=%zz", &v) && v.items.len == 0, "a bad escape in a query");
    v.free();

    /* RFC 3986 5.4 */
    url.parse("http://a/b/c/d;p?q", &gBase);
    resolves("g:h", "g:h");
    resolves("g", "http://a/b/c/g");
    resolves("./g", "http://a/b/c/g");
    resolves("g/", "http://a/b/c/g/");
    resolves("/g", "http://a/g");
    resolves("//g", "http://g");
    resolves("?y", "http://a/b/c/d;p?y");
    resolves("g?y", "http://a/b/c/g?y");
    resolves("#s", "http://a/b/c/d;p?q#s");
    resolves("g#s", "http://a/b/c/g#s");
    resolves("g?y#s", "http://a/b/c/g?y#s");
    resolves(";x", "http://a/b/c/;x");
    resolves("g;x", "http://a/b/c/g;x");
    resolves("g;x?y#s", "http://a/b/c/g;x?y#s");
    resolves("", "http://a/b/c/d;p?q");
    resolves(".", "http://a/b/c/");
    resolves("./", "http://a/b/c/");
    resolves("..", "http://a/b/");
    resolves("../", "http://a/b/");
    resolves("../g", "http://a/b/g");
    resolves("../..", "http://a/");
    resolves("../../", "http://a/");
    resolves("../../g", "http://a/g");
    check(gResolveBad == 0, "RFC 3986 5.4.1, normal examples");
    gResolveBad = 0;
    resolves("../../../g", "http://a/g");
    resolves("../../../../g", "http://a/g");
    resolves("/./g", "http://a/g");
    resolves("/../g", "http://a/g");
    resolves("g.", "http://a/b/c/g.");
    resolves(".g", "http://a/b/c/.g");
    resolves("g..", "http://a/b/c/g..");
    resolves("..g", "http://a/b/c/..g");
    resolves("./../g", "http://a/b/g");
    resolves("./g/.", "http://a/b/c/g/");
    resolves("g/./h", "http://a/b/c/g/h");
    resolves("g/../h", "http://a/b/c/h");
    resolves("g;x=1/./y", "http://a/b/c/g;x=1/y");
    resolves("g;x=1/../y", "http://a/b/c/y");
    resolves("g?y/./x", "http://a/b/c/g?y/./x");
    resolves("g#s/../x", "http://a/b/c/g#s/../x");
    check(gResolveBad == 0, "RFC 3986 5.4.2, abnormal examples");
    gBase.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
