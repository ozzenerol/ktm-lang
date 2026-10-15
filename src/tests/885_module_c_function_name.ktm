import cstr, fs, libc, mem, proc, test;

/* A program function with the C name of a module's extern "c" function
 * took the module's calls: with `u64 strlen()` in the program,
 * libc.strlen("four") called it (both were _K06strlen), AOT and JIT. A
 * module's C function is now called, and its address taken, by the
 * module's name for it (libc.strlen), which becomes its C symbol only at
 * the end. A module's own extern "c" call (in a module compiled with the
 * program) can't be taken either. */

/* the program's own, with the C name */
u64 strlen(u8 *s) {
    return 1000;
}

i64 getpid() {
    return -7;
}

#define DIR "./ktm-885"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) \"$K\" ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
#define ENV "K=\"$(pwd)/../../ktm\"; cd " DIR " && "

void put(u8 *path, u8 *text) { fs.writeAll(cstr.view(path), cstr.view(text)); }

/* a module calling its own extern "c" strlen, used by a program that
 * has a strlen of its own: run compiled or in the JIT */
bool moduleCall(bool jit) {
    u8 *o = proc.shellOutput("%s", jit ? ENV KTM "-jit main.ktm 2>&1"
                                       : ENV KTM "main.ktm -o app 2>&1 && ./app").take();
    bool ok = o && libc.strstr(o, "5 1000") != null;
    if (!ok) { printf("\n  %s got: %s\n", jit ? "jit" : "aot", o); }
    mem.free(o);
    return ok;
}

i32 main() {
    printf("Test - a module's C function and a program function of its name: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (strlen("four") == 1000 && libc.strlen("four") == 4) { correct++; }
    else { printf("\n  strlen %d, libc.strlen %d\n", strlen("four"), libc.strlen("four")); }

    /* addresses: each its own */
    fn(u8 *) u64 c = &libc.strlen;
    fn(u8 *) u64 mine = &strlen;
    tests++;
    if (c("abc") == 3 && mine("abc") == 1000) { correct++; }
    else { printf("\n  &libc.strlen %d, &strlen %d\n", c("abc"), mine("abc")); }

    /* another C function: libc.getpid is the process's */
    tests++;
    if (getpid() == -7 && libc.getpid() > 0) { correct++; }
    else { printf("\n  getpid %d, libc.getpid %d\n", getpid(), libc.getpid()); }

    /* a module's own extern "c" call */
    proc.shell("rm -rf " DIR " && mkdir -p " DIR "/wrap");
    put(DIR "/wrap/wrap.ktm",
        "module wrap;\n"
        "extern \"c\" u64 strlen(u8 *s);\n"
        "pub u64 len(u8 *s) { return strlen(s); }\n");
    put(DIR "/main.ktm",
        "import wrap;\n"
        "u64 strlen(u8 *s) { return 1000; }\n"
        "i32 main() { printf(\"%d %d\\n\", wrap.len(\"hello\"), strlen(\"x\")); return 0; }\n");
    tests++;
    if (moduleCall(false)) { correct++; }
    tests++;
    if (moduleCall(true)) { correct++; }
    proc.shell("rm -rf " DIR);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
