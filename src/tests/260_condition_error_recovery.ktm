#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* An error inside the parentheses of an if, while, do-while, for or
 * switch was recovered from by skipping to the next `;`: in
 * `if (F(1, 2)) a; else b;` that is the one before the `else`, which was
 * then reported as a second, bogus error ("Keyword 'else' cannot be used
 * in this context"). Now the rest of the parentheses is skipped and the
 * statement is parsed on, else branches and bodies included, so only the
 * real errors are reported - including real ones in the bodies. Covers
 * argument-count and type errors, undefined names and a missing `)`. */

#define IFR_SRC "./ktm-260-ifr.ktm"
#define IFR_OUT "./ktm-260-ifr.out"

/* Compile `source` and return what ktm printed */
u8 *diagnose(u8 *source) {
    proc.shell("rm -f " IFR_SRC " " IFR_OUT);
    fs.writeAll(IFR_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " IFR_SRC " -o /dev/null > " IFR_OUT " 2>&1");
    u8 *out = fs.readAll(IFR_OUT);
    proc.shell("rm -f " IFR_SRC " " IFR_OUT);
    return out;
}

i64 count(u8 *s, u8 *what) {
    i64 n = 0;
    while (s && (s = libc.strstr(s, what))) {
        n++;
        s++;
    }
    return n;
}

/* ktm prints `errors` errors, `warnings` warnings, and `want` */
bool prints(u8 *source, i64 errors, i64 warnings, u8 *want) {
    u8 *out = diagnose(source);
    bool ok = out && count(out, "error:") == errors &&
                        count(out, "warning:") == warnings && libc.strstr(out, want);
    if (!ok) { printf("got: '%s'\n", out); }
    mem.free(out);
    return ok;
}

#define PRE "struct P { i64 x; };\nvoid *F(i64 a) { return null; }\nvoid main() {\n  i64 y = 1; P p;\n"

i32 main() {
    printf("Test - error recovery in conditions: ");
    i64 tests = 0;
    i64 correct = 0;

    tests++;
    if (prints(PRE "  if (F(1, 2)) y = 1;\n  else y = 2;\n}\n", 1, 0,
                         "Unexpected number of arguments 2 in call to F()")) {
        correct++;
                         } else { printf("Failed - the report\n"); }

    tests++;
    if (prints(PRE "  if (zz > 1) y = 1;\n  else if (y) y = 2;\n  else y = 3;\n}\n",
                         1, 0, "260-ifr.ktm:5:7\n")) {
        correct++;
                         } else { printf("Failed - undefined name, else-if chain\n"); }

    tests++;
    if (prints(PRE "  if (y) y = 1;\n  else if (p + 1) y = 2;\n  else y = 3;\n}\n",
                         1, 0, "260-ifr.ktm:6:14\n")) {
        correct++;
                         } else { printf("Failed - type error in an else-if\n"); }

    tests++;
    if (prints(PRE "  if (y == 1 {\n    y = 2;\n  } else {\n    y = 3;\n  }\n}\n",
                         1, 0, "Expected ';' or ')' in `if (...)`, got `{`\n --> ./ktm-260-ifr.ktm:5:14\n")) {
        correct++;
                         } else { printf("Failed - missing ) before {\n"); }

    tests++;
    if (prints(PRE "  if (y == 1 && (y > 0 {\n    y = 2;\n  } else {\n    y = zz;\n  }\n}\n",
                         2, 0, "260-ifr.ktm:8:9\n")) {
        correct++;
                         } else { printf("Failed - missing ) and an error in the else body\n"); }

    tests++;
    if (prints(PRE "  if (F((1, 2)) y = 1;\n  else y = 2;\n}\n", 1, 0, "260-ifr.ktm:5:11\n")) {
        correct++;
    } else { printf("Failed - unbalanced parentheses\n"); }

    tests++;
    if (prints(PRE "  while (zz) { y = qq; }\n  y = 4;\n}\n", 2, 0, "260-ifr.ktm:5:20\n")) {
        correct++;
    } else { printf("Failed - while, and its body still checked\n"); }

    tests++;
    if (prints(PRE "  while () y++;\n  y = 4;\n}\n", 1, 0,
                         "Expected the `while` condition, got `)`")) {
        correct++;
                         } else { printf("Failed - empty while\n"); }

    tests++;
    if (prints(PRE "  do { y++; } while (zz);\n  y = 1 2;\n}\n", 2, 0, "260-ifr.ktm:6:9\n")) {
        correct++;
    } else { printf("Failed - do-while\n"); }

    tests++;
    if (prints(PRE "  for (y = 0; zz < 3; y++) { y++; }\n  y = 1 2;\n}\n", 2, 0,
                         "260-ifr.ktm:6:9\n")) {
        correct++;
                         } else { printf("Failed - for\n"); }

    tests++;
    if (prints(PRE "  switch (zz) { case 1: y = 2; break; default: y = qq; }\n}\n",
                         2, 0, "260-ifr.ktm:5:52\n")) {
        correct++;
                         } else { printf("Failed - switch\n"); }

    tests++;
    if (prints(PRE "  switch [zz] { case 1: y = 2; }\n  y = 1 2;\n}\n", 2, 0, "260-ifr.ktm:6:9\n")) {
        correct++;
    } else { printf("Failed - switch []\n"); }

    tests++;
    if (prints(PRE "  if (i64 k = qq; k > 1) y = k;\n  else y = 3;\n}\n", 1, 0, "260-ifr.ktm:5:15\n")) {
        correct++;
    } else { printf("Failed - if with an init statement\n"); }

    /* An error in a range for's body is not taken for one in its header */
    tests++;
    if (prints("void main() {\n  i64 xs[3];\n  for (i64 x : xs) { x = zz; }\n  i64 y = 1 2;\n}\n",
                         2, 0, "260-ifr.ktm:4:13\n")) {
        correct++;
                         } else { printf("Failed - range for body\n"); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
