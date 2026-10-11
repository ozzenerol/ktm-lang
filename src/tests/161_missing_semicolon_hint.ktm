import fs, libc, mem, proc, str, test;

/* The "perhaps a missing `;`" note went to the last token of the
 * nearest earlier line whatever the error was: `i64 x = 2 3;` after
 * `void Main() {` put it on `{`. It is now given only when the unexpected
 * token starts its line, at the end of the line before, and not when
 * that line already ends in `;` or `{`. */

#define SH_TEST_FILE "__161_missing_semicolon_hint.ktm"
#define SH_TEST_OUT  "__161_missing_semicolon_hint.out"
#define SH_HINT "perhaps a missing `;`"

void cleanupHintTest(void) {
    fs.remove(SH_TEST_FILE);
    fs.remove(SH_TEST_OUT);
}

/* Compile `source` with `ktm -jit`: does it fail, print `expected` (if
 * given) and not print `unwanted` (if given)? */
bool failsWith(u8 *source, u8 *expected, u8 *unwanted) {
    u8 *out;
    i64 res;
    bool good = true;

    cleanupHintTest();
    if (!fs.writeAll(SH_TEST_FILE, source, str.len(source),
                                 libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " SH_TEST_FILE " > " SH_TEST_OUT " 2>&1");
    if (res == 0) { good = false; }
    out = fs.readAll(SH_TEST_OUT);
    if (!out) {
        cleanupHintTest();
        return false;
    }
    if (expected && !libc.strstr(out, expected)) { good = false; }
    if (unwanted && libc.strstr(out, unwanted)) { good = false; }
    if (!good) { printf("%s\n", out); }
    mem.free(out);
    cleanupHintTest();
    return good;
}

i32 main() {
    i64 correct = 0, tests = 0;

    printf("Test - placement of the missing `;` hint: ");

    /* the unexpected token is in the middle of its line: no hint */
    tests++;
    if (failsWith("void main() {\n  i64 x = 2 3;\n}\n",
                                "Unexpected integer `3`", SH_HINT)) { correct++; }
    tests++;
    if (failsWith("void main() {\n  i64 y = 1;\n  i64 x = 2 3;\n}\n",
                                "Unexpected integer `3`", SH_HINT)) { correct++; }
    tests++;
    if (failsWith("void main() {\n  i64 x = 1;\n  x = (2 3;\n}\n",
                                "unexpected integer `3`", "previous statement starts here")) {
        correct++;
                                }

    /* it starts its line and the line before has no `;`: hint there */
    tests++;
    if (failsWith("void main() {\n  i64 x = 1\n  return;\n}\n",
                                "info: " SH_HINT " at the end of this line?\n"
                                " --> " SH_TEST_FILE ":2:11\n"
                                "     |\n"
                                "   2 |      i64 x = 1\n", null)) { correct++; }
    tests++;
    if (failsWith("void main() {\n  i64 x = 1\n\n  // gap\n  i64 y = 2;\n}\n",
                                SH_TEST_FILE ":2:11", null)) { correct++; }

    /* the line before already ends in `;` or `{`: no hint */
    tests++;
    if (failsWith("void main() {\n  i64 x = 1;\n  ) x;\n}\n", null, SH_HINT)) {
        correct++;
    }
    tests++;
    if (failsWith("void main()\n{\n  ) x;\n}\n", null, SH_HINT)) { correct++; }

    cleanupHintTest();
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
