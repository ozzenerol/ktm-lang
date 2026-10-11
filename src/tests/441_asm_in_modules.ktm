import fs, libc, mem, proc, test;

/* A module's asm blocks and `_extern` bindings: a module's file is read
 * once per pass (declarations, then in full), and an asm block was
 * "already defined" the second time, so the stdlib's asm (mem, math,
 * coro, ...) could not be modules. Now the declarations pass learns its
 * functions and only the full pass emits the block (once). A binding is
 * the module's name (`m.twice`), pub or private like any declaration.
 * The fixture is src/tests/modules/asmmod; AOT and JIT. */

#define OUT "./ktm-441-asm.out"
#define BIN "./ktm-441-asm.bin"
#define FIX "${KTM_TEST_SRC:-.}/modules/asmmod"
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
    printf("Test - asm in modules: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (output(KTM FIX "/main.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, "10 12\n")) {
        correct++;
    }
    tests++;
    if (output(KTM "-jit " FIX "/main.ktm > " OUT " 2>&1", "10 12\n")) { correct++; }
    /* the block is emitted once */
    tests++;
    if (output(KTM "-S " FIX "/main.ktm -o " BIN " && grep -c '^_DOUBLE_IT:' " BIN " > " OUT, "1\n")) {
        correct++;
    }
    tests++;
    if (output(KTM "-S " FIX "/priv.ktm -o /dev/null > " OUT " 2>&1",
                         "error: `hidden` is private to module m")) {
        correct++;
                         }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
