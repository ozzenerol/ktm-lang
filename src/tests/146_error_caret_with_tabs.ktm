import fs, libc, mem, proc, str, test;

/* The `^` under an error drifted left on a line with tabs: the excerpt
 * echoed the tab, which the terminal shows several columns wide, but the
 * `^` line was padded with one space per character. Tabs are now expanded
 * to spaces (to the next multiple of 8) in both lines, so the `^` lands
 * under the token and a tab inside the underlined span is covered. The
 * reported column is still the 1-based character column. */

#define TAB_TEST_FILE "__146_error_caret_with_tabs.ktm"
#define TAB_TEST_OUT  "__146_error_caret_with_tabs.out"

void cleanupTabTest(void) {
    fs.remove(TAB_TEST_FILE);
    fs.remove(TAB_TEST_OUT);
    fs.remove("./a.out");
}

/* Does compiling `source` fail with `expected` in the output? */
bool excerptIs(u8 *source, u8 *expected) {
    u8 *out;
    i64 res;

    cleanupTabTest();

    if (!fs.writeAll(TAB_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " TAB_TEST_FILE " > " TAB_TEST_OUT " 2>&1");
    if (res == 0) {
        cleanupTabTest();
        return false;
    }

    out = fs.readAll(TAB_TEST_OUT);
    if (!out) {
        cleanupTabTest();
        return false;
    }

    if (!libc.strstr(out, expected)) {
        printf("%s\n", out);
        mem.free(out);
        cleanupTabTest();
        return false;
    }

    mem.free(out);
    cleanupTabTest();
    return true;
}

i32 main() {
    i64 correct = 0, tests = 7;

    printf("Test - error caret on a line with tabs: ");

    /* a tab of indentation */
    if (excerptIs(
            "void main() {\n\ti64 x = 2 3;\n}\n",
            TAB_TEST_FILE ":2:12\n"
            "     |\n"
            "   2 |            i64 x = 2 3;\n"
            "     |                      ^\n")) { correct++; }

    /* two tabs, and tabs between the tokens */
    if (excerptIs(
            "void main() {\n\t\ti64 x =\t2\t3;\n}\n",
            TAB_TEST_FILE ":2:13\n"
            "     |\n"
            "   2 |                    i64 x = 2       3;\n"
            "     |                                    ^\n")) { correct++; }

    /* a tab that moves less than 8 columns, to the next stop */
    if (excerptIs(
            "void main() {\n  i64 abc =\t2 3;\n}\n",
            TAB_TEST_FILE ":2:15\n"
            "     |\n"
            "   2 |      i64 abc =     2 3;\n"
            "     |                      ^\n")) { correct++; }

    /* a tab inside a string before the error */
    if (excerptIs(
            "void main() {\n  u8 *s = \"a\tb\" 3;\n}\n",
            TAB_TEST_FILE ":2:17\n"
            "     |\n"
            "   2 |      u8 *s = \"a    b\" 3;\n"
            "     |                       ^\n")) { correct++; }

    /* a wide underline after a tab */
    if (excerptIs(
            "void main() {\n\ti64 x = 1;\n\tx = 2 + undefined_thing;\n}\n",
            TAB_TEST_FILE ":3:10\n"
            "     |\n"
            "   3 |            x = 2 + undefined_thing;\n"
            "     |                    ^^^^^^^^^^^^^^^\n")) { correct++; }

    /* the issue's repro: tabs before and inside the underlined span */
    if (excerptIs(
            "#define N 3\n\t#define Z (N\t? 2.50)\nU0 Main() {}\n",
            TAB_TEST_FILE ":2:12\n"
            "     |\n"
            "   2 |            #define Z (N    ? 2.50)\n"
            "     |                      ^^^^^^^^^^^^^\n")) { correct++; }

    /* no tabs: unchanged */
    if (excerptIs(
            "void main() {\n    i64 x = 2 3;\n}\n",
            TAB_TEST_FILE ":2:15\n"
            "     |\n"
            "   2 |        i64 x = 2 3;\n"
            "     |                  ^\n")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
