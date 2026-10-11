import fs, libc, mem, proc, str, test;

/* An error on a #define line echoed a rewritten line in its excerpt:
 * `#define T (N > 2 ? 1 : 2.5)` showed as `#define T (N > 2  1 :
 * 2.500000)`. The renderer re-lexes the line to colour it and printed
 * each token from its value: a number came out as `%ld`/`%f` (`0x1F`
 * as 31, `2.5` as 2.500000, `1e2` as 100.000000), a char const lost its
 * quotes, and a character the lexer skips (`?`) vanished, moving the
 * text out from under the `^`. The excerpt is the line as written. */

#define ECHO_TEST_FILE "__138_define_error_echo.ktm"
#define ECHO_TEST_OUT  "__138_define_error_echo.out"

void cleanupEchoTest(void) {
    fs.remove(ECHO_TEST_FILE);
    fs.remove(ECHO_TEST_OUT);
    fs.remove("./a.out");
}

/* Does compiling `source` fail with `expected` (the excerpt's source
 * line, and its `^` line) in the output? */
bool excerptIs(u8 *source, u8 *expected) {
    u8 *out;
    i64 res;

    cleanupEchoTest();

    if (!fs.writeAll(ECHO_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " ECHO_TEST_FILE " > " ECHO_TEST_OUT " 2>&1");
    if (res == 0) {
        cleanupEchoTest();
        return false;
    }

    out = fs.readAll(ECHO_TEST_OUT);
    if (!out) {
        cleanupEchoTest();
        return false;
    }

    if (!libc.strstr(out, expected)) {
        printf("%s\n", out);
        mem.free(out);
        cleanupEchoTest();
        return false;
    }

    mem.free(out);
    cleanupEchoTest();
    return true;
}

i32 main() {
    i64 correct = 0, tests = 6;

    printf("Test - error excerpt shows the line as written: ");

    /* the issue's repro: a macro, a `$` and a float */
    if (excerptIs(
            "#define N 3\n#define T (N > 2 $ 1 : 2.5)\nvoid main() {}\n",
            "   2 |    #define T (N > 2 $ 1 : 2.5)\n     |              ^^^^^^^^^^^^^^^^^")) { correct++; }

    /* hex, exponent, char const and string macro in a #define */
    if (excerptIs(
            "#define N 3\n#define S \"x\\ty\"\n#define T (N + 0x1F + 1e2 + 'a' + S)\nvoid main() {}\n",
            "   3 |    #define T (N + 0x1F + 1e2 + 'a' + S)\n     |              ^^^^^^^^^^^^^^^^^^^^^^^^^^")) { correct++; }

    /* comments kept in a #define */
    if (excerptIs(
            "#define N 3\n#define X (N + 1.5 /* c */ + \"s\")   // tail\nvoid main() {}\n",
            "   2 |    #define X (N + 1.5 /* c */ + \"s\")   // tail\n     |              ^^^^^^^^^^^^^^^^^^^^^^^")) { correct++; }

    /* an #if with a float and a `$` */
    if (excerptIs(
            "#define N 3\n#if N > 2.5 $ 1\n#endif\nvoid main() {}\n",
            "   2 |    #if N > 2.5 $ 1\n     |                  ^")) { correct++; }

    /* a statement using a macro next to number literals */
    if (excerptIs(
            "#define N 3\nvoid main() { i64 x = N + 0x10 + 1e3 + 2.5 3; }\n",
            "   2 |    void main() { i64 x = N + 0x10 + 1e3 + 2.5 3; }\n     |                                               ^")) { correct++; }

    /* a char const and an escaped string */
    if (excerptIs(
            "void main() { i64 x = 'ab' + \"a\\tb\\\"c\" + 0x10 + 1e3 + 10U + 1.5f64; }\n",
            "   1 |    void main() { i64 x = 'ab' + \"a\\tb\\\"c\" + 0x10 + 1e3 + 10U + 1.5f64; }\n")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
