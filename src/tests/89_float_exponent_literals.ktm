import fs, json, libc, mem, proc, str, test;

#define EXP_TEST_FILE "__89_float_exp_fail.ktm"
#define EXP_TEST_OUT  "__89_float_exp_fail.out"

/* `1e3` used to lex as the integer 1 (strtoll stops at the `e`) and
 * `1.0e-7` as `1.0e` followed by `- 7`. A literal with an exponent is
 * an f64 whether or not it has a `.`. */
bool testExponentLiterals(void) {
    bool ok = true;
    f64 a = 1e3, b = 1E3, c = 1e+3, d = 1e-3, e = 1.5e3, f = .5e2, g = 2.e1;
    f64 h = 1.0e-7, k = 1.0e21, m = 1e308, n = 1e-320, z = 0e0;

    if (a != 1000.0) { printf("1e3 = %f\n", a); ok = false; }
    if (b != 1000.0) { printf("1E3 = %f\n", b); ok = false; }
    if (c != 1000.0) { printf("1e+3 = %f\n", c); ok = false; }
    if (d != 0.001) { printf("1e-3 = %f\n", d); ok = false; }
    if (e != 1500.0) { printf("1.5e3 = %f\n", e); ok = false; }
    if (f != 50.0) { printf(".5e2 = %f\n", f); ok = false; }
    if (g != 20.0) { printf("2.e1 = %f\n", g); ok = false; }
    if (h != 0.0000001) { printf("1.0e-7 = %e\n", h); ok = false; }
    if (k != 1000000000000000000000.0) { printf("1.0e21 = %e\n", k); ok = false; }
    if (m < 9.9e307 || m > 1.1e308) { printf("1e308 = %e\n", m); ok = false; }
    /* 1e-320 is a denormal: non-zero and below the smallest normal. */
    if (n <= 0.0 || n >= 2.2e-308) { printf("1e-320 = %e\n", n); ok = false; }
    if (z != 0.0) { printf("0e0 = %f\n", z); ok = false; }
    /* In an expression, and negated */
    if (1e3 + 1 != 1001.0) { printf("1e3 + 1 wrong\n"); ok = false; }
    if (-2e2 != -200.0) { printf("-2e2 wrong\n"); ok = false; }
    if (2e2-1 != 199.0) { printf("2e2-1 wrong\n"); ok = false; }
    return ok;
}

/* `e`/`E` are hex digits; binary literals, `...` ranges and the
 * f32/f64 suffix share the same scanner. */
bool testNeighbours(void) {
    bool ok = true;
    i64 x = 0x1E3, y = 0xe, w = 0b1010, hit = 0;
    f64 s = 1.5e1f64, t = 2e2f32, u = 2.5f64;

    if (x != 483) { printf("0x1E3 = %d\n", x); ok = false; }
    if (y != 14) { printf("0xe = %d\n", y); ok = false; }
    if (0x1E+3 != 33) { printf("0x1E+3 wrong\n"); ok = false; }
    if (w != 10) { printf("0b1010 = %d\n", w); ok = false; }
    if (s != 15.0) { printf("1.5e1f64 = %f\n", s); ok = false; }
    if (t != 200.0) { printf("2e2f32 = %f\n", t); ok = false; }
    if (u != 2.5) { printf("2.5f64 = %f\n", u); ok = false; }
    switch (2) {
        case 1...3: hit = 1; break;
    }
    if (!hit) { printf("case 1...3 missed\n"); ok = false; }
    return ok;
}

/* JSON numbers like `1e+15` have no `.` but are still floats. */
bool testJsonExponent(void) {
    bool ok = true;
    json.Value *j = json.parse("{\"a\": 1e+15, \"b\": 2E3, \"c\": -5e-1, \"d\": 1.5e2, \"e\": 42}");
    json.Value *o;

    if (!j) { return false; }
    o = json.select(j, ".a");
    if (!o || o.type != json.FLOAT || o.floatVal != 1e15) { printf("json 1e+15 wrong\n"); ok = false; }
    o = json.select(j, ".b");
    if (!o || o.type != json.FLOAT || o.floatVal != 2000.0) { printf("json 2E3 wrong\n"); ok = false; }
    o = json.select(j, ".c");
    if (!o || o.type != json.FLOAT || o.floatVal != -0.5) { printf("json -5e-1 wrong\n"); ok = false; }
    o = json.select(j, ".d");
    if (!o || o.type != json.FLOAT || o.floatVal != 150.0) { printf("json 1.5e2 wrong\n"); ok = false; }
    o = json.select(j, ".e");
    if (!o || o.type != json.INT || o.intVal != 42) { printf("json 42 wrong\n"); ok = false; }
    return ok;
}

void cleanupExpTest(void) {
    fs.remove(EXP_TEST_FILE);
    fs.remove(EXP_TEST_OUT);
    fs.remove("./a.out");
}

/* An exponent with no digits must be a normal compile error with the
 * file and line, not silently `1`. */
bool compileFailsWith(u8 *source, u8 *expected) {
    u8 *out;
    i64 res;

    cleanupExpTest();

    if (!fs.writeAll(EXP_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " EXP_TEST_FILE " > " EXP_TEST_OUT " 2>&1");
    if (res != 1) {
        printf("ktm exit status %d, expected a normal compile failure\n", res);
        cleanupExpTest();
        return false;
    }

    out = fs.readAll(EXP_TEST_OUT);
    if (!out) {
        cleanupExpTest();
        return false;
    }

    if (!libc.strstr(out, expected)) {
        printf("%s\n", out);
        mem.free(out);
        cleanupExpTest();
        return false;
    }

    mem.free(out);
    cleanupExpTest();
    return true;
}

bool testMissingExponentDigits(void) {
    bool ok = true;
    if (!compileFailsWith("void main()\n{\n  f64 x = 1e;\n}\n",
                                                EXP_TEST_FILE ":3:")) { ok = false; }
    if (!compileFailsWith("void main()\n{\n  f64 x = 1e+;\n}\n",
                                                "malformed numeric literal")) { ok = false; }
    if (!compileFailsWith("void main()\n{\n\n  f64 x = 1.5E-;\n}\n",
                                                EXP_TEST_FILE ":4:")) { ok = false; }
    if (!compileFailsWith("void main()\n{\n  f64 x = 1e3e2;\n}\n",
                                                "malformed numeric literal")) { ok = false; }
    return ok;
}

i32 main() {
    printf("Test - Float exponent literals: \n");
    i64 tests = 4;
    i64 correct = 0;

    if (testExponentLiterals()) { correct++; }
    else { printf("Failed - TestExponentLiterals()\n"); }

    if (testNeighbours()) { correct++; }
    else { printf("Failed - TestNeighbours()\n"); }

    if (testJsonExponent()) { correct++; }
    else { printf("Failed - TestJsonExponent()\n"); }

    if (testMissingExponentDigits()) { correct++; }
    else { printf("Failed - TestMissingExponentDigits()\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
