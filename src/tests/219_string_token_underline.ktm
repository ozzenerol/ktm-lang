import cstr, fs, libc, mem, proc, test;

/* The underline under a string token in a diagnostic was the length of
 * its contents, starting at the opening quote, so it left out the end of
 * the string and the closing quote. It now covers the whole token, as
 * does the one under a character constant (whose escapes were counted
 * as one character). */

#define STRU_SRC "./ktm-219-stru.ktm"
#define STRU_OUT "./ktm-219-stru.out"

/* Compile `source` and return what ktm printed */
u8 *diagnose(u8 *source) {
    proc.shell("rm -f " STRU_SRC " " STRU_OUT);
    fs.writeAll(STRU_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " STRU_SRC " -o /dev/null > " STRU_OUT " 2>&1");
    u8 *out = fs.readAll(STRU_OUT).take();
    proc.shell("rm -f " STRU_SRC " " STRU_OUT);
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
    printf("Test - string token underline: ");
    i64 tests = 0;
    i64 correct = 0;

    tests++;
    if (has("i64 s = 2 \"abcd\" 3;\n", "Unexpected string `\"abcd\"`",
                    "219-stru.ktm:1:11\n", "|              ^^^^^^\n")) {
        correct++;
                    } else { printf("Failed - string\n"); }

    tests++;
    if (has("i64 s = 2 \"a\\nb\" 3;\n", "Unexpected string",
                    "|              ^^^^^^\n")) {
        correct++;
                    } else { printf("Failed - string with an escape\n"); }

    tests++;
    if (has("i64 s = 2 \"\" 3;\n", "Unexpected string",
                    "|              ^^\n")) {
        correct++;
                    } else { printf("Failed - empty string\n"); }

    tests++;
    if (has("i64 s = 2 'ab' 3;\n", "Unexpected character constant",
                    "|              ^^^^\n")) {
        correct++;
                    } else { printf("Failed - character constant\n"); }

    tests++;
    if (has("i64 s = 2 '\\n' 3;\n", "Unexpected character constant",
                    "|              ^^^^\n")) {
        correct++;
                    } else { printf("Failed - escaped character constant\n"); }

    tests++;
    if (has("i64 s = 2 '\\'' 3;\n", "Unexpected character constant",
                    "|              ^^^^\n")) {
        correct++;
                    } else { printf("Failed - escaped quote\n"); }

    /* Other tokens are unchanged */
    tests++;
    if (has("i64 s = 2 abc 3;\n", "219-stru.ktm:1:11\n",
                    "|              ^^^\n")) {
        correct++;
                    } else { printf("Failed - identifier\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
