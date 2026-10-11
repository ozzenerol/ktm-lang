import test;

import fs, libc, mem, proc, str;

/* Inside a function `P p; i64 z = p;` was only a warning and stored the
 * class's address, while `z = p` and a global `i64 gz = gp;` are errors.
 * A local declaration the `=` operator rejects is now the same error,
 * and the only diagnostic. */

#define CI_SRC "./ktm-222-ci.ktm"
#define CI_OUT "./ktm-222-ci.out"

/* Compile `source`; `*_status` gets ktm's exit code; returns its output */
u8 *diagnose(u8 *source, i64 *_status) {
    proc.shell("rm -f " CI_SRC " " CI_OUT);
    fs.writeAll(CI_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    *_status = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " CI_SRC " -o /dev/null > " CI_OUT " 2>&1");
    u8 *out = fs.readAll(CI_OUT);
    proc.shell("rm -f " CI_SRC " " CI_OUT);
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

/* ktm fails with exactly one error, containing `want` and `where` */
bool oneError(u8 *source, u8 *want, u8 *where) {
    i64 status;
    u8 *out = diagnose(source, &status);
    bool ok = status != 0 && out && count(out, "error:") == 1 &&
                        count(out, "warning:") == 0 && libc.strstr(out, want) &&
                        libc.strstr(out, where);
    if (!ok) { printf("got (status %d): '%s'\n", status, out); }
    mem.free(out);
    return ok;
}

/* ktm compiles it with no diagnostics */
bool clean(u8 *source) {
    i64 status;
    u8 *out = diagnose(source, &status);
    bool ok = status == 0 && out && !libc.strstr(out, "error:") &&
                        !libc.strstr(out, "warning:");
    if (!ok) { printf("got (status %d): '%s'\n", status, out); }
    mem.free(out);
    return ok;
}

#define CLS "struct P { i64 x, y; };\nstruct Q { i64 a; };\n"

i32 main() {
    printf("Test - local class to integer is an error: ");
    i64 tests = 0, correct = 0;

    tests++;
    if (oneError(CLS "void main() {\n  P p;\n  i64 z = p;\n}\n",
                             "`i64 = P` is invalid", "222-ci.ktm:5:9\n")) { correct++; }
    tests++;
    if (oneError(CLS "void main() {\n  P p;\n  u8 c = p;\n}\n",
                             "`u8 = P` is invalid", "222-ci.ktm:5:8\n")) { correct++; }
    tests++;
    if (oneError(CLS "void main() {\n  P p;\n  f64 f = p;\n}\n",
                             "`f64 = P` is invalid", "222-ci.ktm:5:9\n")) { correct++; }
    tests++;
    if (oneError(CLS "void main() {\n  P p;\n  i64 *ip = p;\n}\n",
                             "`i64 * = P` is invalid", "222-ci.ktm:5:11\n")) { correct++; }
    /* A class value into a class of another type was accepted silently */
    tests++;
    if (oneError(CLS "void main() {\n  P p;\n  Q q = p;\n}\n",
                             "`Q = P` is invalid", "222-ci.ktm:5:7\n")) { correct++; }
    /* Same message as the assignment and the global */
    tests++;
    if (oneError(CLS "void main() {\n  P p;\n  i64 z;\n  z = p;\n}\n",
                             "`i64 = P` is invalid", "222-ci.ktm:6:5\n") &&
            oneError(CLS "P gp;\nI64 gz = gp;\n",
                             "`i64 = P` is invalid", "222-ci.ktm:4:8\n")) { correct++; }
    /* Still in a declaration list */
    tests++;
    if (oneError(CLS "void main() {\n  P p;\n  i64 a = 1, b = p;\n}\n",
                             "`i64 = P` is invalid", "222-ci.ktm:5:16\n")) { correct++; }

    /* Legal declarations are unchanged */
    tests++;
    if (clean(CLS "void main() {\n  P p;\n  p.x = 1;\n  P same = p;\n"
                        "  P *pp = &p;\n  void *u = &p;\n  void *v = pp;\n  auto a = p;\n"
                        "  i64 x = 1, y = p.x;\n  f64 f = 2;\n  u8 *s = \"hi\";\n"
                        "  i64 *ip = &p.y;\n  printf(\"%d %d %d %d\\n\", same.x, a.x, x, y);\n}\n")) {
        correct++;
                        }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
