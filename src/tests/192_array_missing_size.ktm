import test;

import fs, libc, mem, proc, str;

/* `void Main() { i64 a[; }` crashed ktm: the array size expression was
 * empty (parseExpr returned null at the `;`) and was evaluated anyway.
 * An array declaration with neither a size nor a `]` is now reported at
 * the token where the `]` was expected, like `i64 a[2;` already was.
 * Each source must fail with exit status 1 (not a signal or a hang). */

#define ARRSZ_TEST_SRC "./ktm-192-arrsize.ktm"
#define ARRSZ_TEST_OUT "./ktm-192-arrsize.out"
#define ARRSZ_TEST_BIN "./ktm-192-arrsize.bin"
/* A single literal: adjacent strings don't concatenate inside #define */
#define ARRSZ_KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

void cleanupArrSizeTest(void) {
    proc.shell("rm -f " ARRSZ_TEST_SRC " " ARRSZ_TEST_OUT " " ARRSZ_TEST_BIN);
}

/* ktm exits 1 and its output has `e1` and `e2` (null to skip) */
bool failsWith(u8 *source, u8 *e1, u8 *e2 = null) {
    u8 *out;
    i64 res;
    bool ok;

    cleanupArrSizeTest();
    fs.writeAll(ARRSZ_TEST_SRC, source, str.len(source),
                        libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    res = proc.shell(ARRSZ_KTM ARRSZ_TEST_SRC " -o " ARRSZ_TEST_BIN " > "
                             ARRSZ_TEST_OUT " 2>&1; test $? -eq 1");
    out = fs.readAll(ARRSZ_TEST_OUT);
    ok = res == 0 && out && libc.strstr(out, e1) && (!e2 || libc.strstr(out, e2));
    if (!ok) { printf("got (%d): '%s'\n", res, out); }
    mem.free(out);
    cleanupArrSizeTest();
    return ok;
}

#define WANT_BRACKET "perhaps you meant `]`"

i32 main() {
    printf("Test - array declaration missing its size: ");
    i64 tests = 0;
    i64 correct = 0;

    /* The repro, reported at the `;` (line 3, column 9) */
    tests++;
    if (failsWith("void main()\n{\n  i64 a[;\n}\nmain;\n",
                                WANT_BRACKET, "arrsize.ktm:3:9")) { correct++; }
    else { printf("Failed - the repro\n"); }

    /* A global, a class field and a parameter */
    tests++;
    if (failsWith("i64 g[;\nI64 h;\n", WANT_BRACKET, "arrsize.ktm:1:7")) {
        correct++;
    } else { printf("Failed - global\n"); }
    tests++;
    if (failsWith("struct C\n{\n  i64 f[;\n};\n", WANT_BRACKET,
                                "arrsize.ktm:3:9")) { correct++; }
    else { printf("Failed - class field\n"); }
    tests++;
    if (failsWith("void F(i64 p[;) {}\n", WANT_BRACKET)) { correct++; }
    else { printf("Failed - parameter\n"); }

    /* The second dimension, and other tokens than `;` */
    tests++;
    if (failsWith("void main()\n{\n  i64 a[2][;\n}\nmain;\n",
                                WANT_BRACKET, "arrsize.ktm:3:12")) { correct++; }
    else { printf("Failed - a[2][\n"); }
    tests++;
    if (failsWith("void main()\n{\n  i64 a[) = 0;\n}\nmain;\n",
                                "unexpected character `)`")) { correct++; }
    else { printf("Failed - a[)\n"); }

    /* At the end of the file */
    tests++;
    if (failsWith("i64 a[", "Unexpected end of input")) { correct++; }
    else { printf("Failed - a[ at end of file\n"); }
    tests++;
    if (failsWith("i64 a[2][", "Unexpected end of input")) { correct++; }
    else { printf("Failed - a[2][ at end of file\n"); }

    /* A size that is there but not constant is still its own error */
    tests++;
    if (failsWith("void main(i64 n)\n{\n  i64 a[n];\n}\nmain(1);\n",
                                "Expected constant integer expression")) { correct++; }
    else { printf("Failed - a[n]\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
