import fs, libc, mem, proc, str, test;

/* A call argument of the wrong type was reported at the call's `)` (or
 * the token after it) instead of at the argument. It is now reported at
 * the argument, underlining all of it. */

#define ARGLOC_SRC "./ktm-216-argloc.ktm"
#define ARGLOC_OUT "./ktm-216-argloc.out"

/* Compile `source` and return what ktm printed */
u8 *diagnose(u8 *source) {
    proc.shell("rm -f " ARGLOC_SRC " " ARGLOC_OUT);
    fs.writeAll(ARGLOC_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " ARGLOC_SRC " -o /dev/null > " ARGLOC_OUT " 2>&1");
    u8 *out = fs.readAll(ARGLOC_OUT);
    proc.shell("rm -f " ARGLOC_SRC " " ARGLOC_OUT);
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

#define CLS "struct P { i64 x, y; };\nstruct S { fn(P *p) void f; };\n" \
                        "void Take(P *p) { }\nvoid TakeV(P p) { }\nvoid Two(i64 a, P *p) { }\n"

i32 main() {
    printf("Test - argument type warning location: ");
    i64 tests = 0;
    i64 correct = 0;

    tests++;
    if (has(CLS "void main() {\n  i64 y = 1;\n  Take(y);\n}\n",
                    "expected 'P *' got 'i64' in function 'Take'",
                    "216-argloc.ktm:8:8\n", "|           ^\n")) {
        correct++;
                    } else { printf("Failed - pointer parameter\n"); }

    tests++;
    if (has(CLS "void main() {\n  i64 y = 1;\n  TakeV(y);\n}\n",
                    "expected 'P' got 'i64' in function 'TakeV'",
                    "216-argloc.ktm:8:9\n")) {
        correct++;
                    } else { printf("Failed - class parameter\n"); }

    tests++;
    if (has(CLS "void main() {\n  i64 y = 1;\n  Two(1, y);\n}\n",
                    "in function 'Two'", "216-argloc.ktm:8:10\n")) {
        correct++;
                    } else { printf("Failed - second argument\n"); }

    tests++;
    if (has(CLS "void main() {\n  i64 y = 1;\n  Take(y + 1);\n}\n",
                    "216-argloc.ktm:8:8\n", "|           ^^^^^\n")) {
        correct++;
                    } else { printf("Failed - expression argument\n"); }

    tests++;
    if (has(CLS "void main() {\n  i64 y = 1;\n  S s;\n  s.f(y);\n}\n",
                    "in function 'f'", "216-argloc.ktm:9:7\n")) {
        correct++;
                    } else { printf("Failed - function pointer field\n"); }

    tests++;
    if (has(CLS "void main() {\n  i64 y = 1;\n  Two(1,\n      y);\n}\n",
                    "in function 'Two'", "216-argloc.ktm:9:7\n")) {
        correct++;
                    } else { printf("Failed - argument on the next line\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
