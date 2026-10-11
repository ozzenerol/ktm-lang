import cstr, fs, libc, mem, proc, test;

/* F14: messages printed some functions by their symbols (`_K06helper`,
 * `_K3geo7missing`) instead of their names: the JIT's unresolved symbol,
 * the linker's undefined references and the note after a failed link,
 * the REPL's function listing. They now print `helper` and
 * `geo.missing`. */

#define D "./ktm-450"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

/* the output of `cmd` run in D */
u8 *run(u8 *cmd) {
    u8 *full = cstr.format("cd " D " && %s > out 2>&1", cmd);
    proc.shell(full);
    mem.free(full);
    return fs.readAll(D "/out");
}

bool has(u8 *out, u8 *what) { return out && libc.strstr(out, what) != null; }

i32 main() {
    printf("Test - names, not symbols, in messages: ");
    i64 correct = 0, tests = 0;
    proc.shell("rm -rf " D " && mkdir -p " D "/geo");
    u8 *mod = "module geo;\npub i64 missing(i64 x);\npub i64 area(i64 w, i64 h) { return w * h; }\n";
    u8 *main = "import geo;\ni64 helper(i64 x);\n"
               "i32 main() { return helper(1) + geo.missing(2) + geo.area(1, 2); }\n";
    fs.writeAll(D "/geo/geo.ktm", mod, cstr.len(mod), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    fs.writeAll(D "/main.ktm", main, cstr.len(main), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);

    /* the JIT */
    u8 *out = run(KTM "-jit main.ktm");
    tests++;
    if (has(out, "JIT: unresolved symbol 'helper'") && !has(out, "_K")) { correct++; }
    else { printf("\nJIT: %s\n", out); }
    mem.free(out);

    /* a failed link: the linker's messages and the notes */
    out = run(KTM "main.ktm -o m");
    tests++;
    if (has(out, "`geo.missing` is declared but not defined") &&
        has(out, "`helper` is declared but not defined") &&
        has(out, "geo.missing") && !has(out, "_K")) { correct++; }
    else { printf("\nAOT: %s\n", out); }
    mem.free(out);

    /* the REPL */
    u8 *in = "import geo;\nreplFindFn(\"area\");\n";
    fs.writeAll(D "/in", in, cstr.len(in), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    out = run(KTM "-repl < in");
    tests++;
    if (has(out, "geo.area(i64 w, i64 h)") && !has(out, "_K")) { correct++; }
    else { printf("\nREPL: %s\n", out); }
    mem.free(out);

    proc.shell("rm -rf " D);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
