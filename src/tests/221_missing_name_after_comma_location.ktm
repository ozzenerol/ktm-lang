import fs, libc, mem, proc, str, test;

/* A global declaration missing a name after a comma, `i64 a, ;`, was
 * reported at the token after the bad one: the start of the next line,
 * or nowhere at the end of the file. It is now reported at the bad
 * token, and the declarations after it are still checked. */

#define CNM_SRC "./ktm-221-cnm.ktm"
#define CNM_OUT "./ktm-221-cnm.out"

/* Compile `source` and return what ktm printed */
u8 *diagnose(u8 *source) {
    proc.shell("rm -f " CNM_SRC " " CNM_OUT);
    fs.writeAll(CNM_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " CNM_SRC " -o /dev/null > " CNM_OUT " 2>&1");
    u8 *out = fs.readAll(CNM_OUT);
    proc.shell("rm -f " CNM_SRC " " CNM_OUT);
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

i32 main() {
    printf("Test - missing name after a comma: ");
    i64 tests = 0;
    i64 correct = 0;

    tests++;
    if (has("i64 a, ;\nI64 b;\n", "Identifier expected", "221-cnm.ktm:1:8\n",
                    "|           ^\n")) {
        correct++;
                    } else { printf("Failed - `;` after the comma\n"); }

    tests++;
    if (has("i64 a, ;", "Identifier expected", "221-cnm.ktm:1:8\n")) {
        correct++;
    } else { printf("Failed - at the end of the file\n"); }

    tests++;
    if (has("i64 a, 5;\n", "Identifier expected", "221-cnm.ktm:1:8\n")) {
        correct++;
    } else { printf("Failed - a number after the comma\n"); }

    tests++;
    if (has("i64 a,\n;\nI64 c;\n", "Identifier expected", "221-cnm.ktm:2:1\n")) {
        correct++;
    } else { printf("Failed - `;` on the next line\n"); }

    /* The next declaration is still checked, at its own token */
    tests++;
    if (has("i64 a, ;\nI64 b, 7;\n", "221-cnm.ktm:1:8\n", "221-cnm.ktm:2:8\n")) {
        correct++;
    } else { printf("Failed - two errors\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
