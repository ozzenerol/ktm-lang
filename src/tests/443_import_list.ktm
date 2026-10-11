import test;

import fs, libc, mem, proc;

/* `import a, b, h = net.http;`: one line imports several modules, each
 * with its own alias if it has one (the stdlib map's decision 3). Before,
 * `import` took one module and `,` was "expected `;` after the module
 * name". The modules load one after the other, in the order written,
 * so one listed before a module it imports still finds it loaded. The fixture is
 * src/tests/modules/importlist; AOT and JIT. */

#define OUT "./ktm-443-imports.out"
#define BIN "./ktm-443-imports.bin"
#define FIX "${KTM_TEST_SRC:-.}/modules/importlist"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
                        "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

bool output(u8 *cmd, u8 *want) {
    proc.shell("rm -f " OUT " " BIN);
    proc.shell(cmd);
    u8 *out = fs.readAll(OUT);
    bool ok = out && libc.strstr(out, want) != null;
    if (!ok) { printf("\n%s\n----\n%s\n", cmd, out); }
    mem.free(out);
    proc.shell("rm -f " OUT " " BIN);
    return ok;
}

i32 main() {
    printf("Test - import lists: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (output(KTM FIX "/main.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, "1 2 3\n")) {
        correct++;
    }
    tests++;
    if (output(KTM "-jit " FIX "/main.ktm > " OUT " 2>&1", "1 2 3\n")) { correct++; }
    /* b imports a: listed first, it still sees a loaded (they load one
     * after the other), no "circular import" */
    tests++;
    if (output(KTM FIX "/reversed.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, "1 2 3\n")) {
        correct++;
    }
    tests++;
    if (output(KTM "-jit " FIX "/reversed.ktm > " OUT " 2>&1", "1 2 3\n")) { correct++; }
    tests++;
    if (output(KTM "-S " FIX "/bad.ktm -o /dev/null > " OUT " 2>&1",
                         "error: expected `;` or `,` after the module name")) {
        correct++;
                         }

    /* missing modules of a list are each reported where written, with
     * their own source line (the rest of a list is read from a file of its
     * own, which keeps the lines and columns) */
    tests++;
    if (output(KTM "-S " FIX "/missing.ktm -o /dev/null > " OUT " 2>&1",
                         "missing.ktm:4:8\n     |\n   4 |           zz1,") &&
            output(KTM "-S " FIX "/missing.ktm -o /dev/null > " OUT " 2>&1",
                         "missing.ktm:5:9\n     |\n   5 |        h = zz2, zz3;") &&
            output(KTM "-S " FIX "/missing.ktm -o /dev/null > " OUT " 2>&1",
                         "missing.ktm:5:14\n")) {
        correct++;
                         }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
