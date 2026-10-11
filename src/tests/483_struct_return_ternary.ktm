import cstr, fs, libc, proc, str, test;

/* Three compiler fixes found on the way to json's Strings (v0.3.0 step
 * 6c): `return f();` of a struct-returning call crashed (it copied from
 * an address the call never had); `c ? a : b` of structs (Strings,
 * slices) couldn't be lowered, and a struct with something else was taken
 * silently (`u8 *p = c ? "x" : s`), now an error; and a field named like
 * an imported module (`v.str.len` with `import str`) was read as the
 * module's name. */

#define SRC_FILE "__483_src.ktm"

struct P { i64 a; i64 b; i64 c; };
struct Q { i64 a; i64 b; };
struct W { u8[] str; i64 n; };

P mk(i64 x) { P p; p.a = x; p.b = 2; p.c = 3; return p; }
P pass(i64 x) { return mk(x); }
Q mkq(i64 x) { Q q; q.a = x; q.b = -x; return q; }
Q passq(i64 x) { return mkq(x); }
String name(u8[] t) { return str.from(t); }
P pick(i64 k) { return k > 0 ? mk(k) : mk(-k * 10); }

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 main(i32 argc, u8 **argv) {
    printf("Test - struct returns, ?: of structs, fields named like modules: ");
    P p = pass(7);
    Q q = passq(5);
    check(p.a == 7 && p.c == 3 && q.a == 5 && q.b == -5, "return of a struct call (24 and 16 bytes)");
    String s = name("direct");
    defer s.free();
    check(s.equals("direct"), "return of a String call");

    P t = argc > 5 ? mk(1) : mk(2);
    String u = argc > 5 ? name("one") : name("two");
    defer u.free();
    u8[] base = "abc";
    u8[] v = argc > 5 ? "lit" : base[1:];
    check(t.a == 2 && u.equals("two") && v.equals("bc") && pick(4).a == 4 && pick(-3).a == 30,
          "?: of structs, Strings, slices");

    W w;
    w.str = "field";
    w.n = w.str.len;
    check(w.n == 5 && w.str.equals("field"), "a field named str with import str");

    u8 *src = "import str;\ni32 main(i32 argc) {\n    String s = str.from(\"x\");\n"
              "    u8 *p = argc > 5 ? \"lit\" : s;\n    return 0;\n}\n";
    fs.writeAll(SRC_FILE, cstr.view(src));
    u8 *out = proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                               "-jit " SRC_FILE " 2>&1");
    check(out && libc.strstr(out, "error: the branches of `?:` have different types"), "?: of a String and a u8 *");
    fs.remove(SRC_FILE);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
