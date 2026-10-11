import fs, libc, mem, proc, str, test;

/* The warning for a value returned from a function that can't return it
 * printed the expression rebuilt from the AST: `return g++;` as `++g`,
 * `return Twice(Twice(1));` as `Twice(Twice(1););`, and could point
 * inside the expression. It now prints the expression as written and
 * points at the `return`, underlining it and the expression. */

#define URE_SRC "./ktm-220-ure.ktm"
#define URE_OUT "./ktm-220-ure.out"

/* Compile `source` and return what ktm printed */
u8 *diagnose(u8 *source) {
    proc.shell("rm -f " URE_SRC " " URE_OUT);
    fs.writeAll(URE_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " URE_SRC " -o /dev/null > " URE_OUT " 2>&1");
    u8 *out = fs.readAll(URE_OUT);
    proc.shell("rm -f " URE_SRC " " URE_OUT);
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

#define PRE "i64 g = 0;\ni64 twice(i64 x) { return x * 2; }\n"

i32 main() {
    printf("Test - void return warning expression: ");
    i64 tests = 0;
    i64 correct = 0;

    tests++;
    if (has(PRE "void A() { return g++; }\n",
                    "unexpected return value 'g++' of type 'i64' expected 'void'",
                    "220-ure.ktm:3:12\n", "|               ^^^^^^^^^^\n")) {
        correct++;
                    } else { printf("Failed - g++\n"); }

    tests++;
    if (has(PRE "void B() { return twice(twice(1)); }\n",
                    "unexpected return value 'twice(twice(1))' of type",
                    "220-ure.ktm:3:12\n")) {
        correct++;
                    } else { printf("Failed - nested call\n"); }

    tests++;
    if (has(PRE "void C() { return  \"a;b\" ; }\n",
                    "unexpected return value '\"a;b\"' of type", "220-ure.ktm:3:12\n")) {
        correct++;
                    } else { printf("Failed - string with a ;\n"); }

    tests++;
    if (has(PRE "i64 *E() { f64 f; return f; }\n",
                    "unexpected return value 'f' of type 'f64' expected 'i64 *'",
                    "220-ure.ktm:3:19\n")) {
        correct++;
                    } else { printf("Failed - pointer return\n"); }

    /* Over two lines: printed from the AST, at the `return` */
    tests++;
    if (has(PRE "void D() { return g +\n  1; }\n",
                    "unexpected return value 'g + 1' of type", "220-ure.ktm:3:12\n")) {
        correct++;
                    } else { printf("Failed - two lines\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
