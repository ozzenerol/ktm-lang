import cstr, fs, libc, mem, proc, test;

/* Packages (v0.20.0): a dependency's warnings aren't shown (the user
 * can't change its code), its errors are; the project's own packages
 * keep their warnings. */

#define DIR "./ktm-781"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) \"$K\" ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
#define ENV "K=\"$(pwd)/../../ktm\"; cd " DIR "/app && "

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

void put(u8 *path, u8 *text) { fs.writeAll(cstr.view(path), cstr.view(text)); }

/* the JIT run of main.ktm: contains want (or, with !want, lacks it) */
void out(u8 *want, bool has, u8 *what) {
    u8 *o = proc.shellOutput("%s", ENV KTM "-jit main.ktm 2>&1").take();
    bool ok = o && (libc.strstr(o, want) != null) == has;
    check(ok, what);
    if (!ok) { printf("  got: %s\n", o); }
    mem.free(o);
}

i32 main() {
    printf("Test - a dependency's warnings: ");
    proc.shell("rm -rf " DIR " && mkdir -p " DIR "/dep " DIR "/app/own");
    put(DIR "/dep/dep.ktm", "module dep;\nimport str;\npub i64 two() { i64 x = \"s\"; return 2; }\n");
    put(DIR "/app/own/own.ktm", "module own;\nimport str;\npub i64 three() { return 3; }\n");
    put(DIR "/app/ktm.mod", "module example.com/me/app\nrequire example.com/x/dep v0.1.0\nreplace example.com/x/dep => ../dep\n");
    put(DIR "/app/main.ktm", "import \"example.com/x/dep\";\nimport \"example.com/me/app/own\";\n"
                             "void main() { printf(\"%d %d\\n\", dep.two(), own.three()); }\n");
    out("2 3", true, "it runs");
    out("dep.ktm", false, "no warning from the dependency");
    out("own.ktm:2", true, "the project's own package keeps its warning");
    put(DIR "/dep/dep.ktm", "module dep;\npub i64 two() { return nope; }\n");
    out("`nope` has not been defined", true, "a dependency's error is shown");
    proc.shell("rm -rf " DIR);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
