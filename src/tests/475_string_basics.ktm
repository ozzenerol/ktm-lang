import cstr, fs, libc, proc, str, test;

/* String (v0.3.0 step 4): the prelude's {ptr, len, cap}, owning its bytes
 * and always NUL-terminated after them. str.new(), str.withCap(n) and
 * str.from(u8[]) make one, s.free() gives its bytes back (and empties it,
 * so a second free does nothing), `defer s.free();` is the idiom. A
 * declared String starts empty. It converts to a u8[] view wherever one
 * is expected, `%s` prints it, s.cstr() is a C string. A copy shares the
 * bytes (clone() doesn't). Its fields are read-only outside module str. */

#define SRC_FILE "__475_src.ktm"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 countOf(u8[] t, u8 c) {
    i64 n = 0;
    for (auto b : t) { if (b == c) { n++; } }
    return n;
}

String greeting(u8[] who) {
    String s = str.from("hello, ");
    s.append(who);
    return s;
}

u8 *errorOf(u8 *code) {
    u8 *src = cstr.format("import str;\ni32 main() {\n    String s = str.from(\"x\");\n"
                         "    %s\n    return 0;\n}\n", code);
    fs.writeAll(SRC_FILE, cstr.view(src));
    return proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                            "-jit " SRC_FILE " 2>&1");
}

i32 main() {
    printf("Test - String: ");

    String s = str.new();
    defer s.free();
    check(s.len == 0 && s.ptr == null && !cstr.cmp(s.cstr(), ""), "str.new() allocates nothing");
    s.append("banana");
    check(s.len == 6 && s.cap >= 6 && s.ptr[6] == 0 && !cstr.cmp(s.cstr(), "banana"), "append, NUL after");
    check(countOf(s, 'a') == 3 && !cstr.cmp(typeof(s), "String"), "a u8[] view, typeof");
    check(!cstr.cmp(cstr.format("<%s|%8s>", s, s), "<banana|  banana>"), "%s prints it");

    String g = greeting("ann");
    defer g.free();
    check(!cstr.cmp(g.cstr(), "hello, ann"), "returned from a function");

    String w = str.withCap(100);
    defer w.free();
    check(w.cap == 100 && w.len == 0 && w.ptr != null, "str.withCap");
    String f = str.from(s[1:4]);
    defer f.free();
    check(!cstr.cmp(f.cstr(), "ana") && f.ptr != s.ptr, "str.from copies");

    String c = s.clone();
    defer c.free();
    c.toUpper();
    check(!cstr.cmp(c.cstr(), "BANANA") && !cstr.cmp(s.cstr(), "banana"), "clone() is its own");
    String shared = s;
    check(shared.ptr == s.ptr, "a copy shares the bytes");

    String d;
    check(d.len == 0 && d.ptr == null, "a declared String is empty");
    d.append("x");
    d.free();
    d.free();
    check(d.len == 0 && d.ptr == null, "free() empties it, a second does nothing");

    u8 *out = errorOf("s.len = 0;");
    check(out && libc.strstr(out, "error: a String's `.len` is read-only"), ".len read-only");
    out = errorOf("s.ptr = null;");
    check(out && libc.strstr(out, "error: a String's `.ptr` is read-only"), ".ptr read-only");
    fs.remove(SRC_FILE);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
