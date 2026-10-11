import fs, libc, mem, proc, test;

/* rt, the runtime: a module in the install's modules/ that is in every
 * program, imported or not, but that no program imports: `import rt;`
 * in a program or in a program's own module is an error, and nothing
 * binds the prefix rt. The standard library's modules may import it.
 * Built into a library with the rest of modules/ (`ktm -lib name
 * <install>/modules`), it is linked rather than compiled in. The
 * fixture is src/tests/modules/rtinstall, copied into a test install;
 * AOT and JIT. */

#define OUT "./ktm-440-rt.out"
#define BIN "./ktm-440-rt.bin"
#define PREFIX "./ktm-440-prefix"
#define APP PREFIX "/app"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm --install-dir=" PREFIX " "

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

#define INTERNAL "error: rt is internal: the runtime is part of every program and can't be imported"

i32 main() {
    printf("Test - rt, the runtime: ");
    i64 correct = 0, tests = 0;

    proc.shell("rm -rf " PREFIX " && D=${KTM_INSTALL_DIR:-/usr/local} && "
                 "mkdir -p " PREFIX "/lib " PREFIX "/modules && "
                 "cp -P $D/lib/libktm.* " PREFIX "/lib/ && "
                 "cp -r $D/modules/prelude " PREFIX "/modules/ && "
                 "S=${KTM_TEST_SRC:-.}/modules/rtinstall && "
                 "cp -r $S/rt $S/util " PREFIX "/modules/ && cp -r $S/app " PREFIX "/");

    /* in a program that imports nothing */
    tests++;
    if (output(KTM "-S " APP "/plain.ktm -o " BIN " && grep -c '^_K2rt7started:' " BIN " > " OUT, "1\n")) {
        correct++;
    }
    tests++;
    if (output(KTM APP "/plain.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, "plain\n")) { correct++; }

    /* a standard library module uses it */
    tests++;
    if (output(KTM APP "/main.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, "42\n")) { correct++; }
    tests++;
    if (output(KTM "-jit " APP "/main.ktm > " OUT " 2>&1", "42\n")) { correct++; }

    /* a program, and a program's module, can't import or name it */
    proc.shell("printf 'import rt;\\ni32 main() { return 0; }\\n' > " APP "/imp.ktm && "
                 "printf 'i32 main() { return rt.started(); }\\n' > " APP "/name.ktm");
    tests++;
    if (output(KTM "-S " APP "/imp.ktm -o /dev/null > " OUT " 2>&1", INTERNAL)) { correct++; }
    tests++;
    if (output(KTM "-S " APP "/usesmine.ktm -o /dev/null > " OUT " 2>&1", INTERNAL)) { correct++; }
    tests++;
    if (output(KTM "-S " APP "/name.ktm -o /dev/null > " OUT " 2>&1",
                         "error: Variable or function `rt` has not been defined")) {
        correct++;
                         }

    /* modules/ built into one library: rt is linked, not compiled in */
    tests++;
    if (output(KTM "-lib stdmods " PREFIX "/modules > " OUT " 2>&1 && "
                         "test -f " PREFIX "/modules/rt/.ktmlib && test ! -e " PREFIX "/modules/prelude/.ktmlib && "
                         "echo built >> " OUT, "built\n")) {
        correct++;
                         }
    tests++;
    if (output(KTM "-S " APP "/plain.ktm -o " BIN " && grep -c '^_K2rt7started:' " BIN " > " OUT, "0\n")) {
        correct++;
    }
    tests++;
    if (output(KTM APP "/main.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, "42\n")) { correct++; }
    tests++;
    if (output(KTM "-jit " APP "/main.ktm > " OUT " 2>&1", "42\n")) { correct++; }

    proc.shell("rm -rf " PREFIX " " OUT " " BIN);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
