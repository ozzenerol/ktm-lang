import fs, libc, proc, str, test;

/* The ways to a slice (v0.3.0 step 2): an array where a slice is expected,
 * a string literal as a u8[] (its bytes without the NUL, not copied),
 * `a[i:j]` of an array (checked against its length) and `p[i:j]` of a
 * pointer (the way in from a raw pointer: unchecked, it has no length).
 * A slice gives its pointer only through `.ptr`. */

#define SRC_FILE "__472_src.ktm"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 count(u8[] s, u8 c) {
    i64 n = 0;
    for (auto b : s) { if (b == c) { n++; } }
    return n;
}

u8[] greeting() { return "hi there"; }

u8 *errorOf(u8 *code) {
    u8 *src = str.format("i32 main() {\n    i64 a[3] = {1, 2, 3};\n    i64 *p = a;\n"
                         "    i64[] s = a;\n    %s\n    return 0;\n}\n", code);
    fs.writeAll(SRC_FILE, src, str.len(src));
    return proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                            "-jit " SRC_FILE " 2>&1");
}

i32 main() {
    printf("Test - conversions to slices: ");

    u8[] lit = "hello";
    check(lit.len == 5 && lit[0] == 'h' && lit[4] == 'o', "a literal");
    check(count("banana", 'a') == 3 && count("", 'a') == 0, "a literal argument");
    u8[] g = greeting();
    check(g.len == 8 && g[3] == 't', "a literal returned");
    check(lit.ptr[5] == 0, "the literal's NUL is still after it");

    i64 a[4] = {1, 2, 3, 4};
    i64[] from = a[1:3];
    check(from.len == 2 && from[0] == 2 && from.ptr == &a[1], "a[1:3] of an array");
    try {
        i64[] bad = a[2:5];
        check(false, "a[2:5] past the array");
    } catch (Error e) {
        check(e.code == ERR_BOUNDS && !str.cmp(e.message, "slice 2:5 out of range for length 4"),
              "a[2:5] past the array");
    }

    i64 *p = a;
    i64[] fromp = p[0:4];
    check(fromp.len == 4 && fromp[3] == 4, "p[0:4] of a pointer");
    i64[] mid = p[1:3];
    check(mid.len == 2 && mid[1] == 3, "p[1:3] of a pointer");
    i64[] sp = p[:2];
    check(sp.len == 2, "p[:2] of a pointer");

    u8 *out = errorOf("i64[] t = p[1:];");
    check(out && libc.strstr(out, "error: `p[a:]` of a pointer needs its end"), "p[a:] is an error");
    out = errorOf("i64 x = 5;\n    i64[] t = x[0:1];");
    check(out && libc.strstr(out, "error: Cannot subscript a integer type"), "slicing an integer");
    out = errorOf("i64[] t = s[0:1.5];");
    check(out && libc.strstr(out, "with a literal of type `f64`"), "a float bound");
    out = errorOf("i64 *q = s;");
    check(out && !libc.strstr(out, "PASS") && libc.strstr(out, "i64[]"), "a slice isn't a pointer");
    fs.remove(SRC_FILE);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
