import cstr, fs, libc, proc, test;

/* The REPL's builtins replDisasm("name") and replDel("name") (were Uf and
 * ReplDel). replDisasm found nothing once a program's functions got their own
 * `_K0` symbols: it looked the bare name up. */

#define IN_FILE "__448_in.ktm"

i32 main() {
    i64 correct = 0, tests = 3;
    printf("Test - REPL builtins replDisasm and replDel: ");

    u8 *src = "i64 fib(i64 n) { if (n < 2) { return n; } return fib(n - 1) + fib(n - 2); }\n"
              "fib(10);\n"
              "replDisasm(\"fib\");\n"
              "replDel(\"fib\");\n"
              "replDel(\"fib\");\n";
    fs.writeAll(IN_FILE, src, cstr.len(src));
    u8 *out = proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                               "-repl < " IN_FILE " 2>&1");
    if (out && libc.strstr(out, "fib @ ")) { correct++; }
    else { printf("\n  replDisasm: [%s]\n", out); }
    if (out && libc.strstr(out, "replDel: removed")) { correct++; }
    else { printf("\n  replDel: [%s]\n", out); }
    if (out && libc.strstr(out, "replDel: nothing named `fib`")) { correct++; }
    else { printf("\n  second replDel: [%s]\n", out); }
    fs.remove(IN_FILE);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
