import cstr, fs, libc, proc, test;

/* Slices `T[]` (v0.3.0 step 1): a pointer and a length, 16 bytes, passed
 * and returned by value like a two-field struct. An array converts to one
 * where a slice is expected (initialiser, assignment, argument, return).
 * `s.len` and `s.ptr` are read-only, `s[i]` reads and writes, range-for
 * goes over the elements, `==` is an error. One without an initialiser
 * is empty, in a struct or an array too. */

#define SRC_FILE "__470_src.ktm"

struct Point { i64 x; i64 y; };
struct Buf { i64 id; u8[] data; };

i64 garr[3] = {7, 8, 9};
i64[] gempty;

i64 sum(i64[] s) {
    i64 t = 0;
    for (i64 i = 0; i < s.len; i++) { t += s[i]; }
    return t;
}
i64[] same(i64[] s) { return s; }
i64[] fromArray() { return garr; }
void twice(i64[] s) { for (i64 i = 0; i < s.len; i++) { s[i] *= 2; } }
i64 sumY(Point[] ps) { i64 t = 0; for (i64 i = 0; i < ps.len; i++) { t += ps[i].y; } return t; }

u8 *errorOf(u8 *code) {
    u8 *src = cstr.format("i32 main() {\n    i64 x[2] = {1, 2};\n    i64[] a = x, b = x;\n"
                         "    %s\n    return 0;\n}\n", code);
    fs.writeAll(SRC_FILE, cstr.view(src));
    return proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                            "-jit " SRC_FILE " 2>&1").take();
}

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 main() {
    printf("Test - slices: ");

    i64 a[4] = {1, 2, 3, 4};
    i64[] s = a;
    check(s.len == 4 && s.ptr == a && sum(s) == 10, "from an array");
    check(!cstr.cmp(typeof(s), "i64[]") && sizeof(i64[]) == 16 && sizeof(s) == 16, "typeof, sizeof");
    twice(a);
    check(a[3] == 8 && sum(same(s)) == 20, "an array argument, written through");
    i64 t = 0;
    for (auto v : s) { t += v; }
    check(t == 20, "range-for");
    fn(i64[]) i64 f = &sum;
    check(f(a) == 20 && sum(fromArray()) == 24, "function pointer, returned array");

    i64[] e;
    check(e.len == 0 && e.ptr == null && gempty.len == 0, "empty when not initialised");
    Buf b;
    check(b.data.len == 0 && b.data.ptr == null, "empty in a struct");
    u8 raw[5] = {'h', 'e', 'l', 'l', 'o'};
    b.data = raw;
    check(b.data.len == 5 && b.data[4] == 'o', "assigned to a field");

    i64 x[2] = {1, 2}, y[3] = {3, 4, 5};
    i64[] rows[2];
    rows[0] = x;
    rows[1] = y;
    i64[][] nested = rows;
    t = 0;
    for (auto r : nested) { for (auto v : r) { t += v; } }
    check(nested.len == 2 && t == 15 && !cstr.cmp(typeof(nested), "i64[][]"), "nested");

    Point pts[2] = {{1, 2}, {3, 4}};
    Point[] ps = pts;
    ps[1].x = 30;
    check(sumY(ps) == 6 && pts[1].x == 30, "of structs");
    i64[] p = x, q = y;
    check(q.len == 3 && p.len == 2, "`i64[] p, q` declares two slices");

    u8 *out = errorOf("auto r = a == b;");
    check(out && libc.strstr(out, "error: `==` can't compare slices") &&
          libc.strstr(out, "identity with a.ptr == b.ptr"), "== is an error");
    out = errorOf("a.len = 9;");
    check(out && libc.strstr(out, "error: a slice's `.len` is read-only"), ".len read-only");
    out = errorOf("a.ptr++;");
    check(out && libc.strstr(out, "error: a slice's `.ptr` is read-only"), ".ptr read-only");
    fs.remove(SRC_FILE);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
