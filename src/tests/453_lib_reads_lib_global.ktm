import fs, libc, mem, proc, str, test;

/* F29: a module built with -lib that reads or writes a global of another
 * -lib module failed to link its shared object ("relocation R_X86_64_PC32
 * against undefined symbol ... can not be used when making a shared
 * object"): the global was reached PC-relatively, as if it were in the
 * same object. A global only declared here (a prebuilt module's, or an
 * `extern` one) is now reached through the GOT. Here top (a library)
 * reads and increments base's globals (another library); AOT and JIT. */

#define D "./ktm-453"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

u8 *run(u8 *cmd) {
    u8 *full = str.format("cd " D " && %s > out 2>&1", cmd);
    proc.shell(full);
    mem.free(full);
    return fs.readAll(D "/out");
}

void put(u8 *path, u8 *text) {
    fs.writeAll(path, text, str.len(text), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
}

bool is(u8 *what, u8 *out, u8 *want) {
    bool ok = out && !str.cmp(out, want);
    if (!ok) { printf("\n%s: [%s]\n", what, out); }
    mem.free(out);
    return ok;
}

i32 main() {
    printf("Test - a library using another library's globals: ");
    i64 correct = 0, tests = 0;
    proc.shell("rm -rf " D " && mkdir -p " D "/base " D "/top");
    put(D "/base/base.ktm", "module base;\npub i64 val = 20;\npub i64 counter = 0;\n");
    put(D "/top/top.ktm",
        "module top;\nimport base;\n"
        "pub i64 get() { base.counter++; return base.val + base.counter; }\n");
    put(D "/main.ktm",
        "import base, top;\n"
        "i32 main() { i64 a = top.get(); i64 b = top.get();\n"
        "  printf(\"%d %d %d\\n\", a, b, base.counter); return 0; }\n");

    tests++;
    if (is("-lib", run(KTM "-fPIC -lib base ./base && " KTM "-fPIC -lib top ./top && echo built"),
           "built\n")) { correct++; }
    tests++; if (is("JIT", run(KTM "-jit main.ktm"), "21 22 2\n")) { correct++; }
    tests++; if (is("AOT", run(KTM "main.ktm -o m && ./m"), "21 22 2\n")) { correct++; }

    proc.shell("rm -rf " D);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
