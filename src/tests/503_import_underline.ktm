import cstr, fs, libc, mem, proc, test;

/* An error on a module later in an import list (`import cstr, rt;`, also
 * `prelude`) underlined it with a length made by subtracting pointers
 * into two different copies of the line: megabytes of `^`. The underline
 * is the import's columns now. */

#define SRC_FILE "__503_src.ktm"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

void small(u8 *src, u8 *want, u8 *what) {
    fs.writeAll(SRC_FILE, cstr.view(src));
    u8 *out = proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                               "-S -o /dev/null " SRC_FILE " 2>&1 | head -c 100000").take();
    fs.remove(SRC_FILE);
    bool ok = out && libc.strstr(out, want) && cstr.len(out) < 2000 &&
              libc.strstr(out, "^^^^^^^^^^^^^^^\n");
    check(ok, what);
    if (!ok && out) { printf("  got %d bytes\n", cstr.len(out)); }
    mem.free(out);
}

i32 main() {
    printf("Test - an import error's underline: ");
    small("import cstr, rt;\nvoid main() {}\n", "error: rt is internal", "rt after another module");
    small("import cstr, fs, mem, rt, proc;\nvoid main() {}\n", "error: rt is internal", "rt in the middle");
    small("import cstr, prelude;\nvoid main() {}\n", "error: the prelude is always imported",
          "prelude after another module");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
