import fs, libc, mem, proc, str, test;

/* ktm syntax v0.2: `_` may appear between two digits of a number
 * literal (its own base: decimal, or hex inside a hex literal) and is
 * ignored: 1_000_000, 0xFF_FF, 0b1010_0101, 1_000.25, 1_0e1_0. Misplaced
 * at the start or end of the digits, next to `.` or the exponent, or
 * doubled, it is an error: "misplaced '_' in number". A leading `_`
 * is still an identifier. Also works in #define bodies and #if. ktm
 * has no octal literals, so there is no 0o form to separate. */

#define SEP_TEST_FILE "__416_digit_sep.ktm"
#define SEP_TEST_OUT  "__416_digit_sep.out"
#define SEP_TEST_BIN  "__416_digit_sep.bin"

void cleanupSepTest(void) {
    fs.remove(SEP_TEST_FILE);
    fs.remove(SEP_TEST_OUT);
    fs.remove(SEP_TEST_BIN);
}

/* Compile `source` with ktm: it must fail normally (exit status 1)
 * and print "misplaced '_' in number". */
bool compileFailsWithMisplaced(u8 *source) {
    u8 *out;
    i64 res;
    bool ok = true;

    cleanupSepTest();

    if (!fs.writeAll(SEP_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " SEP_TEST_FILE " -o " SEP_TEST_BIN " > " SEP_TEST_OUT " 2>&1");
    if (res != 1) {
        printf("ktm exit status %d, expected a normal compile failure\n", res);
        ok = false;
    } else {
        out = fs.readAll(SEP_TEST_OUT);
        if (!out || !libc.strstr(out, "misplaced '_' in number")) {
            if (out) { printf("%s\n", out); }
            ok = false;
        }
        mem.free(out);
    }

    cleanupSepTest();
    return ok;
}

#define BIG 1_000_000
#if BIG > 999_999
i64 gFromDefine = BIG;
#else
i64 g_from_define = -1;
#endif

i64 rangeCase(i64 i) {
    switch (i) {
        case 1_0...3_0: return 1;
        default:        return 0;
    }
}

/* The valid forms, each compared to the plain literal. */
bool testValidLiterals(void) {
    bool ok = true;
    i64 a = 1_000_000, b = 0xFF_FF, c = 0b1010_0101, d = 0x1_0U;
    f64 e = 1_000.25, f = 1_0e1_0, g = 1.5_0e-1_0;

    if (a != 1000000) { printf("  1_000_000 = %d\n", a); ok = false; }
    if (b != 0xFFFF) { printf("  0xFF_FF = %d\n", b); ok = false; }
    if (c != 0b10100101) { printf("  0b1010_0101 = %d\n", c); ok = false; }
    if (d != 0x10) { printf("  0x1_0U = %d\n", d); ok = false; }
    if (e != 1000.25) { printf("  1_000.25 = %f\n", e); ok = false; }
    if (f != 1.0e11) { printf("  1_0e1_0 = %f\n", f); ok = false; }
    if (g != 1.5e-10) { printf("  1.5_0e-1_0 = %.20f\n", g); ok = false; }
    if (gFromDefine != 1000000) { printf("  #define BIG = %d\n", gFromDefine); ok = false; }
    if (rangeCase(20) != 1 || rangeCase(5) != 0) { printf("  case ranges wrong\n"); ok = false; }
    return ok;
}

/* A leading `_` is still an identifier, not a number. */
i64 underscoreName(void) {
    i64 _100 = 7;
    return _100;
}

/* Every misplaced form. */
bool testMisplaced(void) {
    bool ok = true;
    u8 *forms[13];
    i64 i, n = 0;

    forms[n++] = "i32 main() { i64 x = 0x_FF; }\n";       /* start, hex */
    forms[n++] = "i32 main() { i64 x = 100_; }\n";         /* end */
    forms[n++] = "i32 main() { f64 x = 1_.5; }\n";         /* before '.' */
    forms[n++] = "i32 main() { f64 x = 1._5; }\n";         /* after '.' */
    forms[n++] = "i32 main() { f64 x = 1e_5; }\n";         /* after 'e' */
    forms[n++] = "i32 main() { f64 x = 1_e5; }\n";         /* before 'e' */
    forms[n++] = "i32 main() { i64 x = 1__0; }\n";         /* doubled */
    forms[n++] = "i32 main() { i64 x = 0b_101; }\n";       /* start, binary */
    forms[n++] = "i32 main() { i64 x = 0b101_; }\n";       /* end, binary */
    forms[n++] = "i32 main() { i64 x = 0xFF__FF; }\n";     /* doubled, hex */

    for (i = 0; i < n; i++) {
        if (!compileFailsWithMisplaced(forms[i])) {
            printf("  form %d: %s", i, forms[i]);
            ok = false;
        }
    }
    return ok;
}

i32 main() {
    printf("Test - digit separators: \n");
    i64 tests = 3;
    i64 correct = 0;

    if (testValidLiterals()) { correct++; }
    else { printf("Failed - TestValidLiterals()\n"); }

    if (underscoreName() == 7) { correct++; }
    else { printf("Failed - UnderscoreName()\n"); }

    if (testMisplaced()) { correct++; }
    else { printf("Failed - TestMisplaced()\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
