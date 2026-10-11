#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* A `\` at the end of a line did not continue it, so a #define
 * written over several lines ended at the first and the rest was
 * lexed as code, with confusing errors. Backslash-newline now joins
 * the lines, as in C. */

#define SUM (1 + \
    2 + \
    3)

#define GREETING \
    "hello"

#define HALF (1.0 \
 / 2)

#if SUM == 6 && \
        HALF == 0.5
i64 gIf = 1;
#else
i64 g_if = 0;
#endif

i64 gAfter = \
    7;

i64 add(i64 a, \
                i64 b) {
    return a + b;
}

#define CL_FILE "__173_define_line_continuation.ktm"
#define CL_OUT  "__173_define_line_continuation.out"

/* Compile `source`: true if it fails and prints `expected` */
bool failsWith(u8 *source, u8 *expected) {
    u8 *out;
    i64 res;
    bool good = true;

    fs.remove(CL_FILE);
    if (!fs.writeAll(CL_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " CL_FILE " > " CL_OUT " 2>&1");
    out = fs.readAll(CL_OUT);
    if (res == 0 || !out || !libc.strstr(out, expected)) { good = false; }
    if (!good && out) { printf("%s\n", out); }
    if (out) { mem.free(out); }
    fs.remove(CL_FILE);
    fs.remove(CL_OUT);
    return good;
}

i32 main() {
    i64 correct = 0, tests = 0;

    printf("Test - line continuation in #define: ");

    tests++; if (SUM == 6) { correct++; }
    tests++; if (!str.cmp(GREETING, "hello")) { correct++; }
    tests++; if (HALF == 0.5) { correct++; }
    tests++; if (gIf == 1) { correct++; }
    tests++; if (gAfter == 7) { correct++; }
    tests++; if (add(2, 3) == 5) { correct++; }

    /* an error on a continued line is reported on that line */
    tests++;
    if (failsWith("i64 x = 1 + \\\n  y;\nvoid main() {}\n",
                                CL_FILE ":2:3")) { correct++; }
    /* a `\` not at the end of a line is still an error */
    tests++;
    if (failsWith("i64 x = 1 \\ 2;\nvoid main() {}\n",
                                "Unexpected character `\\`")) { correct++; }
    /* lines after a continued #define keep their numbers */
    tests++;
    if (failsWith("#define A (1 + \\\n 2)\ni64 x = A;\ni64 y = z;\nvoid main() {}\n",
                                CL_FILE ":4:9")) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
