import fs, libc, mem, proc, str, test;

/* A function pointer declared without a name, `i64 (*)(i64 x);`, was
 * reported ("Expected function pointer name") and then, after the
 * recovery restarted inside its parameter list, again as "Cannot handle
 * ')'". Now it is reported once, at the token in place of the name, as a
 * global, a local, a parameter and a class field, and the code after it
 * is still checked. */

#define FPN_SRC "./ktm-225-fpn.ktm"
#define FPN_OUT "./ktm-225-fpn.out"

/* Compile `source` and return what ktm printed */
u8 *diagnose(u8 *source) {
    proc.shell("rm -f " FPN_SRC " " FPN_OUT);
    fs.writeAll(FPN_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " FPN_SRC " -o /dev/null > " FPN_OUT " 2>&1");
    u8 *out = fs.readAll(FPN_OUT);
    proc.shell("rm -f " FPN_SRC " " FPN_OUT);
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

#define MSG "Expected function pointer name got: TK_PUNCT      )"

i32 main() {
    printf("Test - function pointer without a name: ");
    i64 tests = 0;
    i64 correct = 0;

    tests++;
    if (prints("i64 (*)(i64 x);\ni64 b = 1;\n", 1, 0, MSG "\n --> ./ktm-225-fpn.ktm:1:7\n")) {
        correct++;
    } else { printf("Failed - global\n"); }

    tests++;
    if (prints("i64 (*)(i64 x) = 0;\n", 1, 0, "225-fpn.ktm:1:7\n")) {
        correct++;
    } else { printf("Failed - global with an initialiser\n"); }

    tests++;
    if (prints("i64 (*5)(i64 x);\n", 1, 0,
                         "Expected function pointer name got: TK_I64        5\n"
                         " --> ./ktm-225-fpn.ktm:1:7\n")) {
        correct++;
                         } else { printf("Failed - a number for the name\n"); }

    tests++;
    if (prints("void F() {\n  i64 (*)(i64 x);\n  i64 y = 2;\n}\n", 1, 0,
                         MSG "\n --> ./ktm-225-fpn.ktm:2:9\n")) {
        correct++;
                         } else { printf("Failed - local\n"); }

    tests++;
    if (prints("void F(i64 (*)(i64 x), i64 (*)(), i64 y) { y = 1; }\n", 2, 0,
                         "225-fpn.ktm:1:30\n")) {
        correct++;
                         } else { printf("Failed - parameters\n"); }

    tests++;
    if (prints("struct C {\n  i64 (*)(i64 x);\n  i64 y;\n};\nC c;\n", 1, 0,
                         MSG "\n --> ./ktm-225-fpn.ktm:2:9\n")) {
        correct++;
                         } else { printf("Failed - class field\n"); }

    /* Errors after it are still found */
    tests++;
    if (prints("i64 (*)(i64 x);\ni64 (*)(i64 x);\ni64 b = 1 2;\n", 3, 0,
                         "225-fpn.ktm:3:11\n")) {
        correct++;
                         } else { printf("Failed - errors after it\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
