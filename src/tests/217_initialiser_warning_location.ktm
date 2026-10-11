import test;

import fs, libc, mem, proc, str;

/* A class initialiser item of the wrong type for its field was reported
 * at the token after it (the `}` or `,`) instead of at the item. It is
 * now reported at the item, underlining all of it: globals, locals,
 * brace-elided nested classes, items on the next line. */

#define IWL_SRC "./ktm-217-iwl.ktm"
#define IWL_OUT "./ktm-217-iwl.out"

/* Compile `source` and return what ktm printed */
u8 *diagnose(u8 *source) {
    proc.shell("rm -f " IWL_SRC " " IWL_OUT);
    fs.writeAll(IWL_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " IWL_SRC " -o /dev/null > " IWL_OUT " 2>&1");
    u8 *out = fs.readAll(IWL_OUT);
    proc.shell("rm -f " IWL_SRC " " IWL_OUT);
    return out;
}

/* ktm's output has every one of `want` */
bool has(u8 *source, u8 *want1, u8 *want2, u8 *want3 = null) {
    u8 *out = diagnose(source);
    bool ok = out && libc.strstr(out, want1) && libc.strstr(out, want2) &&
                        (!want3 || libc.strstr(out, want3));
    if (!ok) { printf("got: '%s'\n", out); }
    mem.free(out);
    return ok;
}

#define CLS "struct Q { i64 a; i64 *p; };\nstruct R { i64 a; Q q; i64 b; };\n" \
                        "f64 f;\n"
#define MSG "expected 'i64 *' got 'f64"

i32 main() {
    printf("Test - initialiser warning location: ");
    i64 tests = 0;
    i64 correct = 0;

    tests++;
    if (has(CLS "Q qq = {1, f};\n", MSG, "217-iwl.ktm:4:12\n",
                    "|               ^\n")) {
        correct++;
                    } else { printf("Failed - global, last item\n"); }

    tests++;
    if (has(CLS "R r = {1, {2, f}, 3};\n", MSG, "217-iwl.ktm:4:15\n")) {
        correct++;
    } else { printf("Failed - nested braced class\n"); }

    tests++;
    if (has(CLS "R r = {1, 2, f, 3};\n", MSG, "217-iwl.ktm:4:14\n")) {
        correct++;
    } else { printf("Failed - brace-elided nested class\n"); }

    tests++;
    if (has(CLS "Q qq = {1, f * 2};\n", MSG, "217-iwl.ktm:4:12\n",
                    "|               ^^^^^\n")) {
        correct++;
                    } else { printf("Failed - expression item\n"); }

    tests++;
    if (has(CLS "void main() {\n  Q lq = {1,\n          f};\n}\n", MSG,
                    "217-iwl.ktm:6:11\n")) {
        correct++;
                    } else { printf("Failed - local, item on the next line\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
