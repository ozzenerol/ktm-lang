import fs, libc, mem, proc, test;

/* Go's rule for an import prefix: a local or a parameter named like one
 * (`u8 *str`, `Box geo`) shadows the module for its scope: there `geo.x`
 * is the local's field or method, not the module's x, and a member it
 * doesn't have is the error "u8 * has no member `one` (note: the local
 * `geo` hides the module geo)". Its own initialiser still sees the module
 * (the scope starts after the declaration), and outside the scope the
 * module is there again. A type name (`geo.Point`) is the module's. A
 * file-scope name like a prefix is still an error (test 422). The
 * fixture is src/tests/modules/shadow; AOT and JIT. */

#define OUT "./ktm-442-shadow.out"
#define BIN "./ktm-442-shadow.bin"
#define FIX "${KTM_TEST_SRC:-.}/modules/shadow"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
                        "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

bool output(u8 *cmd, u8 *want) {
    proc.shell("rm -f " OUT " " BIN);
    proc.shell(cmd);
    u8 *out = fs.readAll(OUT).take();
    bool ok = out && libc.strstr(out, want) != null;
    if (!ok) { printf("\n%s\n----\n%s\n", cmd, out); }
    mem.free(out);
    proc.shell("rm -f " OUT " " BIN);
    return ok;
}

i32 main() {
    printf("Test - a local shadows an import prefix: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (output(KTM FIX "/main.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, "2 8 4\n")) {
        correct++;
    }
    tests++;
    if (output(KTM "-jit " FIX "/main.ktm > " OUT " 2>&1", "2 8 4\n")) { correct++; }
    /* the local's scope starts after its declaration: its initialiser
     * still sees the module (`i64 geo = geo.one() + 1;`) */
    tests++;
    if (output(KTM FIX "/init.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, "2\n")) {
        correct++;
    }
    tests++;
    if (output(KTM "-jit " FIX "/init.ktm > " OUT " 2>&1", "2\n")) { correct++; }
    /* fields and methods of the local, the module again after its scope */
    tests++;
    if (output(KTM FIX "/members.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, "43 42 1\n")) {
        correct++;
    }
    tests++;
    if (output(KTM "-jit " FIX "/members.ktm > " OUT " 2>&1", "43 42 1\n")) { correct++; }
    tests++;
    if (output(KTM "-S " FIX "/local.ktm -o /dev/null > " OUT " 2>&1",
                         "error: u8 * has no member `one` (note: the local `geo` hides the module geo)")) {
        correct++;
                         }
    /* a type name is the module's */
    tests++;
    if (output(KTM "-S " FIX "/param.ktm -o /dev/null > " OUT " 2>&1 && echo ok >> " OUT, "ok\n")) {
        correct++;
    }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
