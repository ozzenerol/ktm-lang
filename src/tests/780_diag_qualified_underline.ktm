import cstr, fs, libc, mem, proc, test;

/* An error on a module-qualified name underlines it as written
 * (`http.nope`: 9 columns), not the compiler's longer name for it
 * (`net.http.nope`). Before v0.20.0 the carets ran past it. */

#define DIR "./ktm-780"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) \"$K\" ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

void carets(u8 *src, u8 *want, u8 *what) {
    fs.writeAll(DIR "/a.ktm", cstr.view(src));
    u8 *o = proc.shellOutput("K=\"$(pwd)/../../ktm\"; cd " DIR " && " KTM "-jit a.ktm 2>&1").take();
    /* the carets, and no more of them after */
    u8 *at = o ? libc.strstr(o, want) : null;
    bool ok = at && at[libc.strlen(want)] != '^';
    check(ok, what);
    if (!ok) { printf("  got: %s\n", o); }
    mem.free(o);
}

i32 main() {
    printf("Test - qualified names are underlined as written: ");
    proc.shell("rm -rf " DIR " && mkdir -p " DIR);
    carets("import net.http;\nvoid main() { http.nope(); }\n", "\n     |                  ^^^^^^^^^", "http.nope");
    carets("import h = net.http;\nvoid main() { h.nope(1); }\n", "\n     |                  ^^^^^^", "an alias, h.nope");
    carets("import str;\nvoid main() { i64 x = str.nothing; }\n", "\n     |                          ^^^^^^^^^^^", "str.nothing");
    proc.shell("rm -rf " DIR);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
