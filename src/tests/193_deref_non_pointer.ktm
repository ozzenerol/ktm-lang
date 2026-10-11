#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* `i64 d = 1 +* 2;` crashed ktm. It parses as `1 + (*2)` and the
 * unary `*` on an integer gave the expression no type (the "Unary type
 * is being assigned as null" warning), which segfaulted later on. `*`
 * on something that is not a pointer or an array is now an error at the
 * `*`. Each source must fail with exit status 1 (not a signal) and the
 * expected message; real two operator expressions still work. */

#define DEREF_TEST_SRC "./ktm-193-deref.ktm"
#define DEREF_TEST_OUT "./ktm-193-deref.out"
#define DEREF_TEST_BIN "./ktm-193-deref.bin"
/* A single literal: adjacent strings don't concatenate inside #define */
#define DEREF_KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

void cleanupDerefTest(void) {
    proc.shell("rm -f " DEREF_TEST_SRC " " DEREF_TEST_OUT " " DEREF_TEST_BIN);
}

/* ktm exits 1 and its output has `e1` and `e2` (null to skip) */
bool failsWith(u8 *source, u8 *e1, u8 *e2 = null) {
    u8 *out;
    i64 res;
    bool ok;

    cleanupDerefTest();
    fs.writeAll(DEREF_TEST_SRC, source, str.len(source),
                        libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    res = proc.shell(DEREF_KTM DEREF_TEST_SRC " -o " DEREF_TEST_BIN " > "
                             DEREF_TEST_OUT " 2>&1; test $? -eq 1");
    out = fs.readAll(DEREF_TEST_OUT);
    ok = res == 0 && out && libc.strstr(out, e1) && (!e2 || libc.strstr(out, e2));
    if (!ok) { printf("got (%d): '%s'\n", res, out); }
    mem.free(out);
    cleanupDerefTest();
    return ok;
}

/* Compiles and prints exactly `expected` */
bool prints(u8 *source, u8 *expected) {
    u8 *out;
    i64 res;
    bool ok;

    cleanupDerefTest();
    fs.writeAll(DEREF_TEST_SRC, source, str.len(source),
                        libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    res = proc.shell(DEREF_KTM DEREF_TEST_SRC " -o " DEREF_TEST_BIN " && "
                             DEREF_TEST_BIN " > " DEREF_TEST_OUT " 2>&1");
    out = fs.readAll(DEREF_TEST_OUT);
    ok = res == 0 && out && !str.cmp(out, expected);
    if (!ok) { printf("got (%d): '%s'\n", res, out); }
    mem.free(out);
    cleanupDerefTest();
    return ok;
}

#define NOT_PTR "The `*` operator cannot be applied to"

i32 main() {
    printf("Test - deref of a non-pointer: ");
    i64 tests = 0;
    i64 correct = 0;

    /* The repro, at the top level, reported at the `*` (column 12) */
    tests++;
    if (failsWith("i64 d = 1 +* 2;\n", NOT_PTR, "deref.ktm:1:12")) { correct++; }
    else { printf("Failed - the repro\n"); }

    /* Inside a function, other operators and other non-pointers */
    tests++;
    if (failsWith("void main()\n{\n  i64 d = 1 -* 2;\n}\nmain;\n",
                                NOT_PTR, "deref.ktm:3:14")) { correct++; }
    else { printf("Failed - 1 -* 2\n"); }
    tests++;
    if (failsWith("void main()\n{\n  i64 x = 3;\n  i64 d = x ** x;\n}\nmain;\n",
                                NOT_PTR, "integer")) { correct++; }
    else { printf("Failed - x ** x\n"); }
    tests++;
    if (failsWith("void main()\n{\n  f64 f = 1.5;\n  f64 d = *f;\n}\nmain;\n",
                                NOT_PTR, "float")) { correct++; }
    else { printf("Failed - *f\n"); }
    tests++;
    if (failsWith("void main()\n{\n  i64 a[2];\n  a[*1] = 0;\n}\nmain;\n",
                                NOT_PTR)) { correct++; }
    else { printf("Failed - a[*1]\n"); }

    /* Two binary operators in a row and a missing operand stay errors */
    tests++;
    if (failsWith("void main()\n{\n  i64 d = 1 */ 2;\n}\nmain;\n",
                                "Second operand missing")) { correct++; }
    else { printf("Failed - 1 */ 2\n"); }
    tests++;
    if (failsWith("void main()\n{\n  i64 d = 1 + / 2;\n}\nmain;\n",
                                "Second operand missing")) { correct++; }
    else { printf("Failed - 1 + / 2\n"); }
    tests++;
    if (failsWith("void main()\n{\n  i64 x;\n  x = * ;\n}\nmain;\n",
                                "Expected expression after unary")) { correct++; }
    else { printf("Failed - x = * ;\n"); }
    tests++;
    if (failsWith("void main()\n{\n  i64 a[3];\n  a[1 +] = 2;\n}\nmain;\n",
                                "Second operand missing")) { correct++; }
    else { printf("Failed - a[1 +]\n"); }

    /* Unary operators after a binary one are still fine */
    tests++;
    if (prints("void runMain()\n{\n  i64 x = 5, a[2];\n  i64 *p = &x;\n"
                         "  a[0] = 9;\n  i64 **pp = &p;\n"
                         "  printf(\"%d %d %d %d %d %d\\n\", 1 + *p, 1 - -2, 1 + &x - &x,\n"
                         "    2 * *p, 1 + *a, 1 + **pp);\n}\n\nvoid main()\n{\n  runMain();\n}\n",
                         "6 3 1 10 10 6\n")) { correct++; }
    else { printf("Failed - valid expressions\n"); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
