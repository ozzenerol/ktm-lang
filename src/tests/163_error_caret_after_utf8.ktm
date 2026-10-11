import test;

import fs, libc, mem, proc, str;

/* The `^` under an error was placed by counting bytes, so it drifted
 * right after non-ASCII text: `€` is 3 bytes but 1 column. A UTF-8
 * character now takes its width on screen (2 columns for wide East
 * Asian characters and emoji) in the `^` line. The reported column is
 * still the byte column, as the LSP expects. */

#define U8_TEST_FILE "__163_error_caret_after_utf8.ktm"
#define U8_TEST_OUT  "__163_error_caret_after_utf8.out"

void cleanupUtf8Test(void) {
    fs.remove(U8_TEST_FILE);
    fs.remove(U8_TEST_OUT);
    fs.remove("./a.out");
}

/* Does compiling `source` fail with `expected` in the output? */
bool excerptIs(u8 *source, u8 *expected) {
    u8 *out;
    i64 res;

    cleanupUtf8Test();

    if (!fs.writeAll(U8_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " U8_TEST_FILE " > " U8_TEST_OUT " 2>&1");
    if (res == 0) {
        cleanupUtf8Test();
        return false;
    }

    out = fs.readAll(U8_TEST_OUT);
    if (!out) {
        cleanupUtf8Test();
        return false;
    }

    if (!libc.strstr(out, expected)) {
        printf("%s\n", out);
        mem.free(out);
        cleanupUtf8Test();
        return false;
    }

    mem.free(out);
    cleanupUtf8Test();
    return true;
}

i32 main() {
    i64 correct = 0, tests = 0;

    printf("Test - error caret after UTF-8 text: ");

    /* `é` after a 3-byte `€`: column 16, one place right of the `€` */
    tests++;
    if (excerptIs(
            "void main() {\n  i64 x = 1 €é;\n}\n",
            U8_TEST_FILE ":2:16\n"
            "     |\n"
            "   2 |      i64 x = 1 €é;\n"
            "     |                 ^\n")) { correct++; }

    /* after a comment holding a 2-byte character */
    tests++;
    if (excerptIs(
            "void main() {\n  /* ü */ i64 x = 1 2;\n}\n",
            U8_TEST_FILE ":2:22\n"
            "     |\n"
            "   2 |      /* ü */ i64 x = 1 2;\n"
            "     |                        ^\n")) { correct++; }

    /* wide characters take two columns */
    tests++;
    if (excerptIs(
            "void main() {\n  u8 *s = \"日本\" 2;\n}\n",
            U8_TEST_FILE ":2:20\n"
            "     |\n"
            "   2 |      u8 *s = \"日本\" 2;\n"
            "     |                     ^\n")) { correct++; }

    /* a tab after a UTF-8 character goes to the next stop on screen */
    tests++;
    if (excerptIs(
            "void main() {\n  u8 *s = \"é\t\" 2;\n}\n",
            U8_TEST_FILE ":2:17\n"
            "     |\n"
            "   2 |      u8 *s = \"é    \" 2;\n"
            "     |                      ^\n")) { correct++; }

    /* an underlined token holding UTF-8 is as wide as it is on screen */
    tests++;
    if (excerptIs(
            "void main() {\n  i64 é = 2;\n}\n",
            U8_TEST_FILE ":2:7\n"
            "     |\n"
            "   2 |      i64 é = 2;\n"
            "     |          ^\n")) { correct++; }

    /* plain ASCII is unchanged */
    tests++;
    if (excerptIs(
            "void main() {\n  i64 x = 2 3;\n}\n",
            U8_TEST_FILE ":2:13\n"
            "     |\n"
            "   2 |      i64 x = 2 3;\n"
            "     |                ^\n")) { correct++; }

    cleanupUtf8Test();
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
