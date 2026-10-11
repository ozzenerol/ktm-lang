#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* `y = p` with an integer `y` and a class `p` printed a type warning
 * and then the error for the same statement. Only the error is printed
 * now; an assignment the operator allows still gets the warning. */

#define DUP_SRC "./ktm-218-dup.ktm"
#define DUP_OUT "./ktm-218-dup.out"

/* Compile `source` and return what ktm printed */
u8 *diagnose(u8 *source) {
    proc.shell("rm -f " DUP_SRC " " DUP_OUT);
    fs.writeAll(DUP_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " DUP_SRC " -o /dev/null > " DUP_OUT " 2>&1");
    u8 *out = fs.readAll(DUP_OUT);
    proc.shell("rm -f " DUP_SRC " " DUP_OUT);
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

#define CLS "struct P { i64 x, y; };\n"

i32 main() {
    printf("Test - one diagnostic for an invalid assignment: ");
    i64 tests = 0;
    i64 correct = 0;

    tests++;
    if (prints(CLS "void main() {\n  i64 y = 1; P p; y = p;\n}\n", 1, 0,
                         "218-dup.ktm:3:21\n")) {
        correct++;
                         } else { printf("Failed - y = p\n"); }

    tests++;
    if (prints(CLS "void main() {\n  i64 y = 1; P p; p = y;\n}\n", 1, 0,
                         "`P = i64` is invalid")) {
        correct++;
                         } else { printf("Failed - p = y\n"); }

    tests++;
    if (prints(CLS "void main() {\n  i64 y = 1; P p; y += p;\n}\n", 1, 0,
                         "`i64 += P` is invalid")) {
        correct++;
                         } else { printf("Failed - y += p\n"); }

    tests++;
    if (prints(CLS "P gp;\nI64 gz = gp;\n", 1, 0, "`i64 = P` is invalid")) {
        correct++;
    } else { printf("Failed - global initialiser\n"); }

    /* A valid operation with a doubtful type is still warned about */
    tests++;
    if (prints("void main() {\n  i64 *q; f64 f;\n  q = f;\n}\n", 0, 1,
                         "Incompatible types 'f64' is not assignable to type 'i64 *'")) {
        correct++;
                         } else { printf("Failed - warning kept\n"); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
