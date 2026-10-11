import test;

import fs, libc, mem, proc, str;

/* A stray `;` at the top level, `i64 x = 1;;`, was taken as the type of
 * the next declaration, which then failed with "Identifier expected:
 * got TK_KEYWORD i64". It is now an empty declaration and skipped,
 * after a variable, a function body, a class, alone, and in the REPL.
 * This file has some itself; the rest compile in a separate ktm. */

;
i64 gSemiX = 1;;
i64 gSemiY = 2;
void semiF() { gSemiX += 10; };
struct SemiC { i64 a; };;
;;
i64 semiG();;
i64 semiG() { return 5; };

#define SEMI_TEST_SRC "./ktm-194-semi.ktm"
#define SEMI_TEST_OUT "./ktm-194-semi.out"
#define SEMI_TEST_BIN "./ktm-194-semi.bin"
/* A single literal: adjacent strings don't concatenate inside #define */
#define SEMI_KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

void cleanupSemiTest(void) {
    proc.shell("rm -f " SEMI_TEST_SRC " " SEMI_TEST_OUT " " SEMI_TEST_BIN);
}

/* Runs `cmd` on `source` (written to SEMI_TEST_SRC), true if it exits
 * with `status` and its output is exactly `expected` (null to skip) or,
 * with `contains`, has it. */
bool runSemi(u8 *source, u8 *cmd, i64 status, u8 *expected,
                         bool contains = false) {
    u8 *out, buf[1024];
    i64 res;
    bool ok;

    cleanupSemiTest();
    fs.writeAll(SEMI_TEST_SRC, source, str.len(source),
                        libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    libc.snprintf(buf, sizeof(buf), "(%s) > " SEMI_TEST_OUT " 2>&1; test $? -eq %d",
                     cmd, status);
    res = proc.shell(buf);
    out = fs.readAll(SEMI_TEST_OUT);
    ok = res == 0 && out &&
             (!expected || (contains && libc.strstr(out, expected)) ||
                (!contains && !str.cmp(out, expected)));
    if (!ok) { printf("got (%d): '%s'\n", res, out); }
    mem.free(out);
    cleanupSemiTest();
    return ok;
}

#define SEMI_AOT SEMI_KTM SEMI_TEST_SRC " -o " SEMI_TEST_BIN " && " SEMI_TEST_BIN
#define SEMI_JIT SEMI_KTM "-jit " SEMI_TEST_SRC
#define SEMI_REPL SEMI_KTM "-repl < " SEMI_TEST_SRC

i32 main() {
    printf("Test - top level stray semicolon: ");
    i64 tests = 0;
    i64 correct = 0;
    SemiC c;

    /* The declarations of this file around its own stray `;`s */
    tests++;
    c.a = 7;
    semiF();
    if (gSemiX == 11 && gSemiY == 2 && c.a == 7 && semiG() == 5) { correct++; }
    else { printf("Failed - this file\n"); }

    /* The repro, a file starting with `;` and a main after `};` */
    u8 *repro = "i64 x = 1;;\ni64 y = 2;\nvoid main()\n{\n"
                            "  printf(\"%d %d\\n\", x, y);\n};\n";
    tests++;
    if (runSemi(repro, SEMI_AOT, 0, "1 2\n")) { correct++; }
    else { printf("Failed - the repro (AOT)\n"); }
    tests++;
    if (runSemi(repro, SEMI_JIT, 0, "1 2\n")) { correct++; }
    else { printf("Failed - the repro (JIT)\n"); }
    tests++;
    if (runSemi(";\nstruct P { i64 v; };;\nvoid main()\n{\n  P p;\n"
                            "  p.v = 4;\n  printf(\"%d\\n\", p.v);\n}\n;;\n", SEMI_AOT, 0, "4\n")) {
        correct++;
                            } else { printf("Failed - leading ; and };;\n"); }

    /* Top level statements (scripting) after stray `;`s */
    tests++;
    if (runSemi("i64 x = 3;;\n;\n\"%d\\n\", x;;\n", SEMI_JIT, 0, "3\n")) {
        correct++;
    } else { printf("Failed - top level statements\n"); }

    /* The REPL */
    tests++;
    if (runSemi("i64 x = 1;;\ni64 y = 2;\n;\nvoid F() { printf(\"f\\n\"); };\n\nvoid main()\n{\n  x + y;\n  F();\n}\n",
                            SEMI_REPL, 0, "3\nf\n")) { correct++; }
    else { printf("Failed - repl\n"); }

    /* A missing declarator after `,` is still an error */
    tests++;
    if (runSemi("i64 a, ;\nI64 b;\n", SEMI_AOT, 1, "Identifier expected", true)) {
        correct++;
    } else { printf("Failed - i64 a, ;\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
