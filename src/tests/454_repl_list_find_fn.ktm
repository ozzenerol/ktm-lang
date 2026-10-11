import cstr, fs, libc, proc, test;

/* The REPL's builtins replListFn() and replFindFn("name") (were ReplListFn
 * and ReplFindFn), camelCase like replDisasm and replDel. */

#define IN_FILE "__454_in.ktm"

i32 main() {
    i64 correct = 0, tests = 2;
    printf("Test - REPL builtins replListFn and replFindFn: ");

    u8 *src = "i64 squareIt(i64 x) { return x * x; }\n"
              "i64 cubeIt(i64 x) { return x * x * x; }\n"
              "replListFn();\n"
              "replFindFn(\"cube\");\n";
    fs.writeAll(IN_FILE, cstr.view(src));
    u8 *out = proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                               "-repl < " IN_FILE " 2>&1");
    /* replListFn lists both, replFindFn("cube") cubeIt again */
    u8 *sq = out ? libc.strstr(out, "squareIt(") : null;
    u8 *cube = out ? libc.strstr(out, "cubeIt(") : null;
    if (sq && cube) { correct++; }
    else { printf("\n  replListFn: [%s]\n", out); }
    if (cube && libc.strstr(cube + 1, "cubeIt(")) { correct++; }
    else { printf("\n  replFindFn: [%s]\n", out); }
    fs.remove(IN_FILE);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
