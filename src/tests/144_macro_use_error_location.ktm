import test;

import fs, libc, mem, proc, str;

/* An error on a macro use pointed at the #define: `i64 x = 2 N;` with
 * `#define N 3` reported "Unexpected integer `3`" at the define's line
 * and column and showed `#define N 3`. The parser is handed the macro's
 * value, which carried the position of the #define; it is now placed
 * where the macro is written, the underline covers the name and a note
 * says which macro it came from. */

#define MACRO_TEST_FILE "__144_macro_use_error_location.ktm"
#define MACRO_TEST_HDR  "__144_macro_use_error_location.ktmh"
#define MACRO_TEST_OUT  "__144_macro_use_error_location.out"

void cleanupMacroTest(void) {
    fs.remove(MACRO_TEST_FILE);
    fs.remove(MACRO_TEST_HDR);
    fs.remove(MACRO_TEST_OUT);
    fs.remove("./a.out");
}

/* Run ktm on `source` (with `header` as MACRO_TEST_HDR if given): does it
 * fail (or, with `ok`, pass) and print `expected`, and not `unwanted`? */
bool compileOutputHas(u8 *source, u8 *header, bool ok, u8 *expected,
                                            u8 *unwanted=null) {
    u8 *out;
    i64 res;
    bool good = true;

    cleanupMacroTest();

    if (header && !fs.writeAll(MACRO_TEST_HDR, header, str.len(header),
                                                     libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    if (!fs.writeAll(MACRO_TEST_FILE, source, str.len(source),
                                 libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " MACRO_TEST_FILE " > " MACRO_TEST_OUT " 2>&1");
    if ((res == 0) != ok) { good = false; }

    out = fs.readAll(MACRO_TEST_OUT);
    if (!out) {
        cleanupMacroTest();
        return false;
    }
    if (!libc.strstr(out, expected)) { good = false; }
    if (unwanted && libc.strstr(out, unwanted)) { good = false; }
    if (!good) {
        printf("%s\n", out);
    }

    mem.free(out);
    cleanupMacroTest();
    return good;
}

i32 main() {
    i64 correct = 0, tests = 9;

    printf("Test - error on a macro use is reported where it is used: ");

    /* the issue's repro */
    if (compileOutputHas(
            "const i64 N = 3;\nvoid main() {\n  i64 x = 2 N;\n}\n", null, false,
            MACRO_TEST_FILE ":3:13\n"
            "     |\n"
            "   3 |      i64 x = 2 N;\n"
            "     |                ^ expanded from macro `N`",
            "#define N 3\n     |")) { correct++; }

    /* a string macro: the underline is the name, not the string */
    if (compileOutputHas(
            "#define STR \"hello\"\nvoid main() {\n  i64 x = 2 STR;\n}\n", null, false,
            MACRO_TEST_FILE ":3:13\n"
            "     |\n"
            "   3 |      i64 x = 2 STR;\n"
            "     |                ^^^ expanded from macro `STR`")) { correct++; }

    /* a float macro */
    if (compileOutputHas(
            "const f64 PI = 3.25;\nvoid main() {\n  f64 x = 2 PI;\n}\n", null, false,
            MACRO_TEST_FILE ":3:13\n"
            "     |\n"
            "   3 |      f64 x = 2 PI;\n"
            "     |                ^^ expanded from macro `PI`")) { correct++; }

    /* a macro defined as another macro */
    if (compileOutputHas(
            "const i64 N = 3;\n#define LONGNAME N\nvoid main() {\n  i64 x = 2 LONGNAME;\n}\n",
            null, false,
            MACRO_TEST_FILE ":4:13\n"
            "     |\n"
            "   4 |      i64 x = 2 LONGNAME;\n"
            "     |                ^^^^^^^^ expanded from macro `LONGNAME`")) { correct++; }

    /* a folded expression macro */
    if (compileOutputHas(
            "const i64 N = 3;\n#define T (N + 1)\nvoid main() {\n  i64 x = 2 T;\n}\n",
            null, false,
            MACRO_TEST_FILE ":4:13\n"
            "     |\n"
            "   4 |      i64 x = 2 T;\n"
            "     |                ^ expanded from macro `T`")) { correct++; }

    /* the "missing `;`" note on a line ending in a macro */
    if (compileOutputHas(
            "const i64 N = 3;\nvoid main() {\n  i64 x = N\n  i64 y = 2;\n}\n", null, false,
            "info: perhaps a missing `;` at the end of this line?\n"
            " --> " MACRO_TEST_FILE ":3:11\n"
            "     |\n"
            "   3 |      i64 x = N\n"
            "     |              ^ expanded from macro `N`")) { correct++; }

    /* a macro from an included file, used on a line of the includer */
    if (compileOutputHas(
            "#include \"" MACRO_TEST_HDR "\"\nvoid main() {\n  i64 x = 2 HN;\n}\n",
            "\n\n\n\n#define HN 7\n", false,
            MACRO_TEST_FILE ":3:13\n"
            "     |\n"
            "   3 |      i64 x = 2 HN;\n"
            "     |                ^^ expanded from macro `HN`")) { correct++; }

    /* macros still expand to their values: integer, float, string,
     * nested, folded, and in #if */
    if (compileOutputHas(
            "#define N 3\n#define PI 1.5\n#define S \"s\"\n#define M N\n"
            "#define T (N * 2)\n#if N > 2\n#define B 1\n#else\nconst i64 B = 0;\n#endif\n"
            "void runMain() { printf(\"%d %.1f %s %d %d %d\\n\", N, PI, S, M, T, B); }\n\nvoid main()\n{\n  runMain();\n}\n",
            null, true, "3 1.5 s 3 6 1\n")) { correct++; }

    /* the same macro used twice on a line: each at its own column */
    if (compileOutputHas(
            "const i64 N = 3;\nvoid main() {\n  i64 x = N + N N;\n}\n", null, false,
            MACRO_TEST_FILE ":3:17\n"
            "     |\n"
            "   3 |      i64 x = N + N N;\n"
            "     |                    ^ expanded from macro `N`")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
