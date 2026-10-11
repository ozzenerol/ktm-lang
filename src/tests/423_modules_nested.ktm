import fs, mem, proc, str, test;

/* Modules (M1): nested modules and aliases. `import web.http;` loads
 * the directory web/http (its files start with `module http;`) and its
 * names are `http.name`; `import h = web.http;` makes them `h.name` as
 * well. A nested module imports its parent like any other module, and a
 * type of one module is used through another's functions. The symbols
 * stay apart: `_K3net4port`, `_K3net4http3get`. The fixture is
 * src/tests/modules/nested; AOT and JIT. */

#define MOD_OUT "./ktm-423-modules.out"
#define MOD_BIN "./ktm-423-modules.bin"
#define FIXTURE "${KTM_TEST_SRC:-.}/modules/nested/main.ktm"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
                        "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

u8 *gWant = "3 80\n82 84 80\n84\n";

bool output(u8 *cmd) {
    proc.shell("rm -f " MOD_OUT " " MOD_BIN);
    proc.shell(cmd);
    u8 *out = fs.readAll(MOD_OUT);
    bool ok = out && !str.cmp(out, gWant);
    if (!ok) { printf("\n%s\n----\n%s\n", cmd, out); }
    mem.free(out);
    proc.shell("rm -f " MOD_OUT " " MOD_BIN);
    return ok;
}

i32 main() {
    printf("Test - modules: nested modules and aliases: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (output(KTM FIXTURE " -o " MOD_BIN " > " MOD_OUT " 2>&1 && " MOD_BIN " >> " MOD_OUT " 2>&1")) {
        correct++;
    }
    tests++;
    if (output(KTM "-jit " FIXTURE " > " MOD_OUT " 2>&1")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
