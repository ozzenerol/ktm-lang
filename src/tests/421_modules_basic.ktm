import cstr, fs, mem, proc, test;

/* Modules (M1): `import geo;` loads the directory geo/ next to the main
 * file; every file there starts with `module geo;`. Outside, the module's
 * names are `geo.name`: functions (also their address), types, enum
 * members and consts (usable in constant expressions). Inside, every name
 * of every file of the module is visible, in any order: a function can
 * call one, and use a global, from a later file. Module names are emitted
 * as their own symbols, so the program's `square` and geo's don't clash.
 * The fixture is src/tests/modules/basic; AOT and JIT. */

#define MOD_OUT "./ktm-421-modules.out"
#define MOD_BIN "./ktm-421-modules.bin"
#define FIXTURE "${KTM_TEST_SRC:-.}/modules/basic/main.ktm"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
                        "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

u8 *gWant = "3 4\n25 12\n2 4 -2 1 7\n1\n";

bool output(u8 *cmd) {
    proc.shell("rm -f " MOD_OUT " " MOD_BIN);
    proc.shell(cmd);
    u8 *out = fs.readAll(MOD_OUT).take();
    bool ok = out && !cstr.cmp(out, gWant);
    if (!ok) { printf("\n%s\n----\n%s\n", cmd, out); }
    mem.free(out);
    proc.shell("rm -f " MOD_OUT " " MOD_BIN);
    return ok;
}

i32 main() {
    printf("Test - modules: basics: ");
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
