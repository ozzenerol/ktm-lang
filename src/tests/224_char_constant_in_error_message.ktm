import cstr, fs, libc, mem, proc, test;

/* A character constant in an error message was printed from its
 * decoded length: `'\n'` (one character) showed as `\`, `'\x41'` as
 * `\`. Messages now show a character constant or a string as written,
 * with its quotes, and the underline covers it. */

#define CCM_SRC "./ktm-224-ccm.ktm"
#define CCM_OUT "./ktm-224-ccm.out"

/* Compile `source` and return what ktm printed */
u8 *diagnose(u8 *source) {
    proc.shell("rm -f " CCM_SRC " " CCM_OUT);
    fs.writeAll(CCM_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " CCM_SRC " -o /dev/null > " CCM_OUT " 2>&1");
    u8 *out = fs.readAll(CCM_OUT).take();
    proc.shell("rm -f " CCM_SRC " " CCM_OUT);
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

#define UNX " perhaps you meant ';' or ','?"

i32 main() {
    printf("Test - character constant in error messages: ");
    i64 tests = 0;
    i64 correct = 0;

    tests++;
    if (has("i64 s = 2 '\\n' 3;\n",
                    "Unexpected character constant `'\\n'`" UNX,
                    "224-ccm.ktm:1:11\n", "|              ^^^^\n")) {
        correct++;
                    } else { printf("Failed - '\\n'\n"); }

    /* Y9b: one character, an escape counting as one */
    tests++;
    if (has("i64 s = 'ab';\ni64 t = '\\x41B';\n",
                    "a character literal holds one character; use a string or an enum",
                    "224-ccm.ktm:1:", "224-ccm.ktm:2:")) {
        correct++;
                    } else { printf("Failed - 'ab'\n"); }

    tests++;
    if (has("i64 s = 2 '\\x41' 3;\n",
                    "Unexpected character constant `'\\x41'`" UNX,
                    "|              ^^^^^^\n")) {
        correct++;
                    } else { printf("Failed - '\\x41'\n"); }

    tests++;
    if (has("i64 s = 2 '\\'' 3;\n", "Unexpected character constant `'\\''`" UNX,
                    "|              ^^^^\n")) {
        correct++;
                    } else { printf("Failed - '\\''\n"); }

    tests++;
    if (has("i64 s = 2 \"a\\tb\" 3;\n", "Unexpected string `\"a\\tb\"`" UNX,
                    "|              ^^^^^^\n")) {
        correct++;
                    } else { printf("Failed - string with an escape\n"); }

    tests++;
    if (has("void F() { if (1) '\\n' }\n",
                    "Unexpected character constant `'\\n'` while parsing if body",
                    "224-ccm.ktm:1:19\n")) {
        correct++;
                    } else { printf("Failed - if body\n"); }

    /* The value is unchanged */
    tests++;
    if ('\n' == 10 && '\x41' == 'A' && '\'' == 39) {
        correct++;
    } else { printf("Failed - values\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
