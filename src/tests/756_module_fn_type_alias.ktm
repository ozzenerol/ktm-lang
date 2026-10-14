import fs, libc, proc, str, test;

/* A type alias to a function type in a module: `pub type H = fn(W *w)
 * void;` failed with "type `m.H` has already been defined" (the module's
 * second pass made a new fn type, and the check compared the pointers).
 * Used from another file of the module and from the program, AOT and JIT;
 * a real duplicate is still an error. */

#define DIR "./ktm-756"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

i64 gCorrect = 0, gTests = 0;

void check(bool ok, u8 *what, u8[] out) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n%s\n", what, out); }
}

void put(u8[] path, u8[] text) {
    fs.writeAll(path, text, libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
}

i32 main() {
    printf("Test - a module's type alias to a function type: ");
    proc.shell("rm -rf " DIR " && mkdir -p " DIR "/m");
    put(DIR "/m/a.ktm", "module m;\npub type H = fn(W *w) void;\npub struct W { i64 x; };\n");
    put(DIR "/m/b.ktm", "module m;\npub void W.call(H h) { h(self); }\n");
    put(DIR "/main.ktm", "import m;\nvoid f(m.W *w) { printf(\"x=%d\\n\", w.x); }\n"
                         "i32 main() { m.W w; w.x = 3; m.H h = &f; w.call(h); return 0; }\n");

    String out = proc.shellOutput(KTM "-jit " DIR "/main.ktm 2>&1");
    check(str.equals(out[0:out.len], "x=3"), "jit", out[0:out.len]);
    out.free();
    out = proc.shellOutput(KTM DIR "/main.ktm -o " DIR "/bin 2>&1 && " DIR "/bin");
    check(str.equals(out[0:out.len], "x=3"), "aot", out[0:out.len]);
    out.free();

    put(DIR "/m/b.ktm", "module m;\npub type H = fn(W *w) void;\n");
    out = proc.shellOutput(KTM "-jit " DIR "/main.ktm 2>&1");
    check(str.contains(out[0:out.len], "type `m.H` has already been defined"), "duplicate", out[0:out.len]);
    out.free();

    proc.shell("rm -rf " DIR);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
