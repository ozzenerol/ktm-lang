import cstr, fs, libc, mem, proc, test;

/* The old forms removed in ktm syntax v0.2 are errors naming what to
 * write: code at file scope, the `Main` entry point, the print statement
 * and a call without `()`. Each is reported once, at its first token,
 * and the rest of the program parses on (no cascade). The REPL's input
 * is file-scope code and keeps working (see 97, 194). */

#define OF_SRC "./ktm-446-old.ktm"
#define OF_OUT "./ktm-446-old.out"

u8 *diagnose(u8 *source) {
    proc.shell("rm -f " OF_SRC " " OF_OUT);
    fs.writeAll(OF_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               "-S " OF_SRC " -o /dev/null > " OF_OUT " 2>&1");
    u8 *out = fs.readAll(OF_OUT).take();
    proc.shell("rm -f " OF_SRC " " OF_OUT);
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

/* exactly one error, with `want` and at `where` */
bool oneError(u8 *source, u8 *want, u8 *where) {
    u8 *out = diagnose(source);
    bool ok = out && count(out, "error:") == 1 && libc.strstr(out, want) &&
              libc.strstr(out, where);
    if (!ok) { printf("\n%s\n----\n%s\n", source, out); }
    mem.free(out);
    return ok;
}

i32 main() {
    printf("Test - old forms removed: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (oneError("i64 g = 3;\nprintf(\"%d\\n\", g);\nvoid main() { }\n",
                 "code at file scope: put it in `main`", "ktm-446-old.ktm:2:1\n")) { correct++; }
    /* a global's initialiser, constant or not, is not code at file scope */
    tests++;
    if (oneError("i64 g = 3;\ni64 h = g * 2;\nvoid main() { x; }\n",
                 "Variable or function `x` has not been defined", "ktm-446-old.ktm:3:15\n")) { correct++; }
    tests++;
    if (oneError("void Main() { }\n", "`Main` is the old entry point: write `main`",
                 "ktm-446-old.ktm:1:6\n")) { correct++; }
    tests++;
    if (oneError("void main() { \"hi\\n\"; }\n", "a print statement: write `printf(\"...\", ...)`",
                 "ktm-446-old.ktm:1:15\n")) { correct++; }
    tests++;
    if (oneError("void f() { }\nvoid main() { f; }\n", "a call needs its `()`: write `f()`",
                 "ktm-446-old.ktm:2:15\n")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
