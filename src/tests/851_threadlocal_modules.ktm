import cstr, fs, libc, mem, proc, test;

/* threadlocal across modules and its errors. A module's `pub threadlocal`
 * is used from the program and its `static threadlocal` from the module's
 * own function, per thread, AOT and JIT, from source and from the module
 * built with -lib (a .a and a .so). JIT code can't reach the storage of a
 * prebuilt library's threadlocal, so that is a clear error, not a wrong
 * copy. `threadlocal` on a local, on a function or with a value only
 * known at run time is a compile error. */

#define D "./ktm-851"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
#define WANT "m 1102 11\nt 5102 51\nm 1203 12\n"

u8 *run(u8 *cmd) {
    u8 *full = cstr.format("cd " D " && %s > out 2>&1", cmd);
    proc.shell(cstr.view(full));
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

bool has(u8 *what, u8 *out, u8 *want) {
    bool ok = out && libc.strstr(out, want);
    if (!ok) { printf("\n%s: [%s]\n", what, out); }
    mem.free(out);
    return ok;
}

i32 main() {
    printf("Test - threadlocal in modules, and its errors: ");
    i64 correct = 0, tests = 0;
    proc.shell("rm -rf " D " && mkdir -p " D "/base");
    put(D "/base/base.ktm",
        "module base;\n"
        "pub threadlocal i64 count = 10;\n"
        "static threadlocal i64 hidden = 1;\n"
        "pub i64 bump() { hidden++; count++; return count * 100 + hidden; }\n");
    put(D "/main.ktm",
        "import base, libc;\n"
        "void *work(void *a) {\n"
        "    base.count = 50;\n"
        "    printf(\"t %d %d\\n\", base.bump(), base.count);\n"
        "    return null;\n"
        "}\n"
        "i32 main() {\n"
        "    printf(\"m %d %d\\n\", base.bump(), base.count);\n"
        "    libc.pthread_t *t;\n"
        "    libc.pthread_create(&t, null, &work, null);\n"
        "    libc.pthread_join(t);\n"
        "    printf(\"m %d %d\\n\", base.bump(), base.count);\n"
        "    return 0;\n"
        "}\n");

    tests++; if (is("source AOT", run(KTM "main.ktm -o m && ./m"), WANT)) { correct++; }
    tests++; if (is("source JIT", run(KTM "-jit main.ktm"), WANT)) { correct++; }

    tests++; if (is("-lib", run(KTM "-fPIC -lib base ./base && echo built"), "built\n")) { correct++; }
    tests++; if (is("-lib AOT", run(KTM "main.ktm -o m2 && ./m2"), WANT)) { correct++; }
    tests++;
    if (has("-lib JIT", run(KTM "-jit main.ktm"),
            "is defined in a prebuilt library, which JIT code can't reach")) {
        correct++;
    }

    put(D "/local.ktm", "void f() { threadlocal i64 x; }\ni32 main() { return 0; }\n");
    tests++;
    if (has("local", run(KTM "local.ktm -o e"),
            "`threadlocal` only goes on a global variable, not a local")) {
        correct++;
    }
    put(D "/func.ktm", "threadlocal i64 f() { return 1; }\ni32 main() { return 0; }\n");
    tests++;
    if (has("function", run(KTM "func.ktm -o e"), "`threadlocal` only goes on a global variable")) {
        correct++;
    }
    put(D "/init.ktm", "i64 g() { return 2; }\nthreadlocal i64 x = g();\ni32 main() { return 0; }\n");
    tests++;
    if (has("run-time value", run(KTM "init.ktm -o e"),
            "a threadlocal global's initial value must be a constant")) {
        correct++;
    }
    put(D "/addr.ktm", "threadlocal i64 x = 1;\nthreadlocal i64 *p = &x;\ni32 main() { return 0; }\n");
    tests++;
    if (has("address of a threadlocal", run(KTM "addr.ktm -o e"),
            "a threadlocal global's initial value must be a constant")) {
        correct++;
    }
    /* a plain global may point at one: main's copy, set at startup */
    put(D "/plain.ktm",
        "threadlocal i64 x = 7;\ni64 *p = &x;\n"
        "i32 main() { printf(\"%d %d\\n\", *p, p == &x); return 0; }\n");
    tests++; if (is("plain AOT", run(KTM "plain.ktm -o p && ./p"), "7 1\n")) { correct++; }
    tests++; if (is("plain JIT", run(KTM "-jit plain.ktm"), "7 1\n")) { correct++; }

    proc.shell("rm -rf " D);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
