import cstr, fs, libc, proc, test;

/* A main-program global has its own symbol (_K0<len><name>), as its
 * functions do (F24): a program's `environ` no longer replaces the C
 * library's. C data is declared with
 * `extern "c" T name;` (new); a plain `extern T name;` is ktm's: the
 * program's global, or in a module the module's. One declared but never
 * defined is an error that says to use extern "c" (the JIT used to run
 * it at address 0 and crash). */

#define DIR "./ktm-458"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what, u8 *out) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s: [%s]\n", what, out); }
}

void put(u8 *path, u8 *src) {
    fs.writeAll(cstr.view(path), cstr.view(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
}

/* ktm's output for DIR/<file>: -jit, or built and run */
u8 *run(u8 *file, bool jit) {
    u8 *ktm = "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
              "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} ";
    if (jit) {
        return proc.shellOutput(cstr.format("%s-jit " DIR "/%s 2>&1", ktm, file));
    }
    return proc.shellOutput(cstr.format("rm -f " DIR "/bin; %s" DIR "/%s -o " DIR "/bin 2>&1 && "
                                       DIR "/bin 2>&1", ktm, file));
}

void both(u8 *file, u8 *want, u8 *what) {
    u8 *out = run(file, true);
    check(out && !cstr.cmp(out, want), cstr.format("%s (JIT)", what), out);
    out = run(file, false);
    check(out && !cstr.cmp(out, want), cstr.format("%s (AOT)", what), out);
}

i32 main() {
    printf("Test - program globals have their own symbols: ");
    proc.shell("rm -rf " DIR " && mkdir -p " DIR "/m");

    /* the program's environ is its own: a child proc.run() starts still
     * gets the real environment (it got none: proc found the program's
     * environ, 0, by dlsym) */
    put(DIR "/own.ktm",
"import proc;
i64 environ = 0;
i64 optind = 6;
i32 main() {
    u8 *argv[4] = {\"sh\", \"-c\", \"echo home=$HOME\", null};
    u8 *out = proc.run(argv);
    printf(\"%d %d %s\", environ, optind, out);
    return 0;
}
");
    u8 *home = libc.getenv("HOME");
    both("own.ktm", cstr.format("0 6 home=%s", home ? home : ""), "a program's environ");

    /* C data by extern \"c\", a program global by plain extern */
    put(DIR "/ext.ktm",
"extern \"c\" u8 **environ;
extern i64 later;
i64 later = 41;
i32 main() {
    i64 n = 0;
    while (environ[n]) { n++; }
    printf(\"%d %d\", n > 0, later + 1);
    return 0;
}
");
    both("ext.ktm", "1 42", "extern \"c\" data and a plain extern");

    /* a module's plain extern is the module's global, in either file */
    put(DIR "/m/a.ktm", "module m;\nextern i64 shared;\nextern \"c\" u8 **environ;\n"
                        "pub i64 get() { return shared + (environ[0] != null); }\n");
    put(DIR "/m/b.ktm", "module m;\ni64 shared = 9;\n");
    put(DIR "/mod.ktm", "import m;\ni32 main() { printf(\"%d\", m.get()); return 0; }\n");
    both("mod.ktm", "10", "a module's plain extern");

    /* declared, never defined: an error that points at extern \"c\" */
    put(DIR "/undef.ktm", "extern i64 opterr;\ni32 main() { printf(\"%d\", opterr); return 0; }\n");
    u8 *out = run("undef.ktm", true);
    check(out && libc.strstr(out, "unresolved symbol 'opterr'") &&
          libc.strstr(out, "extern \"c\""), "undefined extern (JIT)", out);
    out = run("undef.ktm", false);
    check(out && libc.strstr(out, "`opterr` is declared but not defined; a C variable "
                                  "is declared with extern \"c\""), "undefined extern (AOT)", out);

    proc.shell("rm -rf " DIR);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
