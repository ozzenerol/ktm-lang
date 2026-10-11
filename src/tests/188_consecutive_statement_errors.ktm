#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* `i64 x = 1 2;` followed by `"ab" y;` in one function crashed ktm.
 * The error for `y` rewound up to 6 tokens looking for a punctuator
 * (`y` isn't one), out of its statement and back into the previous
 * one; the per-statement recovery then resynced from that earlier `;`,
 * parsed `"ab" y;` again and looped (a crash in a release build). The
 * error now points at `y`, recovery never resumes before the statement
 * that failed, and a stray `}` left at the top level by a recovery is
 * skipped instead of starting the next declaration. Each source must
 * fail with exit status 1 (not a signal or a hang) and report every
 * error. */

#define STMTERR_TEST_SRC "./ktm-188-stmterr.ktm"
#define STMTERR_TEST_OUT "./ktm-188-stmterr.out"
/* A single literal: adjacent strings don't concatenate inside #define */
#define STMTERR_KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

void cleanupStmtErrTest(void) {
    proc.shell("rm -f " STMTERR_TEST_SRC " " STMTERR_TEST_OUT " a.out");
}

/* ktm exits 1 and its output has each of `e1`..`e3` (null to skip) */
bool failsWith(u8 *source, u8 *e1, u8 *e2 = null, u8 *e3 = null) {
    u8 *out;
    i64 res;
    bool ok;

    cleanupStmtErrTest();
    fs.writeAll(STMTERR_TEST_SRC, source, str.len(source),
                        libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    res = proc.shell(STMTERR_KTM STMTERR_TEST_SRC " > " STMTERR_TEST_OUT " 2>&1; "
                             "test $? -eq 1");
    out = fs.readAll(STMTERR_TEST_OUT);
    ok = res == 0 && out && libc.strstr(out, e1) &&
             (!e2 || libc.strstr(out, e2)) && (!e3 || libc.strstr(out, e3));
    if (!ok) { printf("got (%d): '%s'\n", res, out); }
    mem.free(out);
    cleanupStmtErrTest();
    return ok;
}

i32 main() {
    printf("Test - consecutive statement errors: ");
    i64 tests = 0;
    i64 correct = 0;

    /* The repro: both errors, the second at `y` (line 4, column 8) */
    tests++;
    if (failsWith("void main()\n{\n  i64 x = 1 2;\n  printf(\"ab\" y);\n}\nmain;\n",
                                "Unexpected integer `2`", "stmterr.ktm:4:8",
                                "Invalid identifier `y`")) { correct++; }
    else { printf("Failed - the repro\n"); }

    /* Alone, `"ab" y;` is reported at `y`, not at `Main` */
    tests++;
    if (failsWith("void main()\n{\n  printf(\"ab\" y);\n}\nmain;\n",
                                "stmterr.ktm:3:8", "Invalid identifier `y`")) { correct++; }
    else { printf("Failed - \"ab\" y; alone\n"); }

    /* Short statements before it: the rewind reached two back */
    tests++;
    if (failsWith("void main()\n{\n  i64 a;\n  a;\n  a;\n  printf(\"ab\" y);\n"
                                "  printf(\"cd\" z);\n}\nmain;\n",
                                "stmterr.ktm:6:8", "stmterr.ktm:7:8")) { correct++; }
    else { printf("Failed - after short statements\n"); }

    /* In a nested block, at the top level, and with a call */
    tests++;
    if (failsWith("void main(i64 v)\n{\n  if (v) {\n    i64 x = 1 2;\n"
                                "    printf(\"ab\" y);\n  }\n}\nmain(1);\n",
                                "stmterr.ktm:4:15", "stmterr.ktm:5:10")) { correct++; }
    else { printf("Failed - nested block\n"); }
    tests++;
    if (failsWith("i64 x = 1 2;\n\"ab\" y;\n",
                                "stmterr.ktm:1:11", "stmterr.ktm:2:6")) { correct++; }
    else { printf("Failed - top level\n"); }
    tests++;
    if (failsWith("void F(i64 a, i64 b) {}\nvoid main()\n{\n  F(1 2);\n"
                                "  F(3 4);\n  i64 q = 5 6;\n}\nmain;\n",
                                "stmterr.ktm:4:7", "stmterr.ktm:5:7", "stmterr.ktm:6:13")) { correct++; }
    else { printf("Failed - calls\n"); }

    /* A stray `}` at the top level (here left by the recovery from a
     * bad `if`) no longer swallows the next declaration or crashes */
    tests++;
    if (failsWith("if (1 { }\nstruct Z;\nZ *p;\n",
                                "in `if (...)`")) { correct++; }
    else { printf("Failed - stray } before class\n"); }
    tests++;
    if (failsWith("}\nclass Z;\n", "Identifier expected")) { correct++; }
    else { printf("Failed - } class\n"); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
