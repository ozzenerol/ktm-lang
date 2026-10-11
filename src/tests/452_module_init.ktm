import cstr, fs, libc, mem, proc, test;

/* M2: a module's globals may have run-time initialisers in a module built
 * with -lib too (before, that was "needs a constant initialiser"). They
 * make the module's `_init`, which runs once; the .ktmlib marker says
 * `init`, and a program calls the `_init` of every module that has one,
 * in load order (a module's imports first), before its own initialisers.
 * Here base is a -lib library, top (importing base) comes from source and
 * the program's own global uses both; AOT and JIT. */

#define D "./ktm-452"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
#define WANT "base init\ntop init\n20 21 42 top\n"

u8 *run(u8 *cmd) {
    u8 *full = cstr.format("cd " D " && %s > out 2>&1", cmd);
    proc.shell(full);
    mem.free(full);
    return fs.readAll(D "/out").take();
}

void put(u8 *path, u8 *text) {
    fs.writeAll(cstr.view(path), cstr.view(text), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
}

bool is(u8 *what, u8 *out, u8 *want) {
    bool ok = out && !cstr.cmp(out, want);
    if (!ok) { printf("\n%s: [%s]\n", what, out); }
    mem.free(out);
    return ok;
}

i32 main() {
    printf("Test - module init functions: ");
    i64 correct = 0, tests = 0;
    proc.shell("rm -rf " D " && mkdir -p " D "/base " D "/top");
    put(D "/base/base.ktm",
        "module base;\n"
        "i64 seed() { printf(\"base init\\n\"); return 20; }\n"
        "pub i64 val = seed();\n"
        "pub i64 twice(i64 x) { return x * 2; }\n");
    put(D "/top/top.ktm",
        "module top;\nimport base;\n"
        "i64 make() { printf(\"top init\\n\"); return base.val + 1; }\n"
        "pub i64 val = make();\npub u8 *name = \"top\";\n");
    put(D "/main.ktm",
        "import base, top;\ni64 g = base.twice(top.val);\n"
        "i32 main() { printf(\"%d %d %d %s\\n\", base.val, top.val, g, top.name); return 0; }\n");

    /* both from source */
    tests++; if (is("source JIT", run(KTM "-jit main.ktm"), WANT)) { correct++; }
    tests++; if (is("source AOT", run(KTM "main.ktm -o m && ./m"), WANT)) { correct++; }

    /* base built with -lib: allowed, and its marker says init */
    tests++;
    if (is("-lib", run(KTM "-fPIC -lib base ./base && grep -x init base/.ktmlib"), "init\n")) {
        correct++;
    }
    tests++; if (is("-lib JIT", run(KTM "-jit main.ktm"), WANT)) { correct++; }
    tests++; if (is("-lib AOT", run(KTM "main.ktm -o m2 && ./m2"), WANT)) { correct++; }

    proc.shell("rm -rf " D);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
