import fs, libc, proc, str, test;

/* A module's pub generic that calls one of the module's `static`
 * functions, the module built with -lib: the program makes the generic's
 * instance and calls the static function by its symbol, which stayed
 * local to the library ("undefined reference to g.twice" at the link,
 * the JIT couldn't find it either). Now the library exports a static
 * function a generic of its module uses (and only those). Found writing
 * net.http (http.decode<T>). AOT and JIT; a nested module (web.util) too. */

#define DIR "./ktm-758"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

i64 gCorrect = 0, gTests = 0;

void check(bool ok, u8 *what, u8[] out) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n%.*s\n", what, out.len, out.ptr); }
}

void put(u8[] path, u8[] text) {
    fs.writeAll(path, text, libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
}

void run(u8 *cmd, u8[] want, u8 *what) {
    String out = proc.shellOutput("%s", cmd);
    check(str.equals(out[0:out.len], want), what, out[0:out.len]);
    out.free();
}

i32 main() {
    printf("Test - a module's generic calling its static function, from a library: ");
    proc.shell("rm -rf " DIR " && mkdir -p " DIR "/g " DIR "/web/util");
    put(DIR "/g/g.ktm", "module g;\nstatic i64 twice(i64 x) { return x * 2; }\n"
                        "static i64 unused(i64 x) { return x + 1; }\n"
                        "pub T dbl<T>(T x) { return (T)twice((i64)x); }\n");
    put(DIR "/web/util/u.ktm", "module util;\nstatic i64 inc(i64 x) { return x + 1; }\n"
                               "pub struct Box<T> { T v; };\n"
                               "pub T Box<T>.next() { return (T)inc((i64)self.v); }\n");
    put(DIR "/main.ktm", "import g, web.util;\n"
                         "i32 main() { util.Box<i32> b = {41}; printf(\"%d %d\\n\", g.dbl<i64>(21), b.next()); return 0; }\n");
    run(KTM "-lib lib " DIR " 2>&1 && echo built", "built", "the library builds");
    run(KTM DIR "/main.ktm -o " DIR "/bin 2>&1 && " DIR "/bin", "42 42", "aot");
    run(KTM "-jit " DIR "/main.ktm 2>&1", "42 42", "jit");
    /* only what a generic uses is exported */
    run("nm " DIR "/liblib.a 2>/dev/null | grep -c ' [TW] .*6unused'", "0", "an unused static stays local");
    proc.shell("rm -rf " DIR);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
