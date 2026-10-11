import cstr, fs, libc, proc, test;

/* `argc` (and `argv`) compiled in any function and was silently 0 (null):
 * the old implicit argc of every function. They are main's parameters
 * now, and anywhere else an error. */

#define SRC_FILE "__462_src.ktm"

u8 *runKtm(u8 *src, u8 *args) {
    fs.writeAll(SRC_FILE, src, cstr.len(src));
    return proc.shellOutput(cstr.format("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                                       "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                                       "-jit " SRC_FILE " %s 2>&1", args));
}

i32 main() {
    i64 correct = 0, tests = 3;
    printf("Test - argc outside main: ");

    u8 *out = runKtm("i64 count() { return argc; }\ni32 main() { printf(\"%d\", count()); return 0; }\n", "");
    if (out && libc.strstr(out, "error: `argc` is not defined here") &&
        libc.strstr(out, "main's parameters")) { correct++; }
    else { printf("\n  argc: [%s]\n", out); }
    out = runKtm("u8 *first() { return argv[0]; }\ni32 main() { printf(\"%s\", first()); return 0; }\n", "");
    if (out && libc.strstr(out, "error: `argv` is not defined here")) { correct++; }
    else { printf("\n  argv: [%s]\n", out); }
    /* main's own */
    out = runKtm("i32 main(i32 argc, u8 **argv) { printf(\"%d %s\", argc, argv[1]); return 0; }\n", "hello");
    if (out && !cstr.cmp(out, "2 hello")) { correct++; }
    else { printf("\n  main: [%s]\n", out); }
    fs.remove(SRC_FILE);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
