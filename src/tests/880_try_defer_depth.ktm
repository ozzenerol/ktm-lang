import cstr, fs, libc, mem, proc, str, test;

/* v0.23.0: up to 256 try blocks and defers (each `ad` variable is one) in
 * scope at once in a function, not 32; past that, an error that names the
 * file and line, not a bare "nesting above 32". */

#define DP_SRC "./ktm-880-deep.ktm"
#define DP_OUT "./ktm-880-deep.out"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

/* 60 scoped Strings, then 12 tries one inside the other */
i64 deep() {
    scoped String s0 = str.from("x");
    scoped String s1 = str.from("x");
    scoped String s2 = str.from("x");
    scoped String s3 = str.from("x");
    scoped String s4 = str.from("x");
    scoped String s5 = str.from("x");
    scoped String s6 = str.from("x");
    scoped String s7 = str.from("x");
    scoped String s8 = str.from("x");
    scoped String s9 = str.from("x");
    scoped String s10 = str.from("x");
    scoped String s11 = str.from("x");
    scoped String s12 = str.from("x");
    scoped String s13 = str.from("x");
    scoped String s14 = str.from("x");
    scoped String s15 = str.from("x");
    scoped String s16 = str.from("x");
    scoped String s17 = str.from("x");
    scoped String s18 = str.from("x");
    scoped String s19 = str.from("x");
    scoped String s20 = str.from("x");
    scoped String s21 = str.from("x");
    scoped String s22 = str.from("x");
    scoped String s23 = str.from("x");
    scoped String s24 = str.from("x");
    scoped String s25 = str.from("x");
    scoped String s26 = str.from("x");
    scoped String s27 = str.from("x");
    scoped String s28 = str.from("x");
    scoped String s29 = str.from("x");
    scoped String s30 = str.from("x");
    scoped String s31 = str.from("x");
    scoped String s32 = str.from("x");
    scoped String s33 = str.from("x");
    scoped String s34 = str.from("x");
    scoped String s35 = str.from("x");
    scoped String s36 = str.from("x");
    scoped String s37 = str.from("x");
    scoped String s38 = str.from("x");
    scoped String s39 = str.from("x");
    scoped String s40 = str.from("x");
    scoped String s41 = str.from("x");
    scoped String s42 = str.from("x");
    scoped String s43 = str.from("x");
    scoped String s44 = str.from("x");
    scoped String s45 = str.from("x");
    scoped String s46 = str.from("x");
    scoped String s47 = str.from("x");
    scoped String s48 = str.from("x");
    scoped String s49 = str.from("x");
    scoped String s50 = str.from("x");
    scoped String s51 = str.from("x");
    scoped String s52 = str.from("x");
    scoped String s53 = str.from("x");
    scoped String s54 = str.from("x");
    scoped String s55 = str.from("x");
    scoped String s56 = str.from("x");
    scoped String s57 = str.from("x");
    scoped String s58 = str.from("x");
    scoped String s59 = str.from("x");
    i64 n = 0;
    try {
    try {
    try {
    try {
    try {
    try {
    try {
    try {
    try {
    try {
    try {
    try {
    n = s0.len + s59.len;
    } catch { n = -1; }
    } catch { n = -1; }
    } catch { n = -1; }
    } catch { n = -1; }
    } catch { n = -1; }
    } catch { n = -1; }
    } catch { n = -1; }
    } catch { n = -1; }
    } catch { n = -1; }
    } catch { n = -1; }
    } catch { n = -1; }
    } catch { n = -1; }
    return n;
}

u8 *compile(u8 *source) {
    proc.shell("rm -f " DP_SRC " " DP_OUT);
    fs.writeAll(DP_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               "-S " DP_SRC " -o /dev/null > " DP_OUT " 2>&1");
    u8 *out = fs.readAll(DP_OUT).take();
    proc.shell("rm -f " DP_SRC " " DP_OUT);
    return out;
}

i32 main() {
    printf("Test - try and defer depth: ");
    check(deep() == 2, "60 ad variables and 12 nested tries in one function");

    String src = str.from("import str;\nvoid f() {\n");
    for (i64 i = 0; i < 300; i++) { src.appendFormat("    ad String t%d = str.from(\"y\");\n", i); }
    src.append("}\ni32 main() { f(); return 0; }\n");
    u8 *out = compile(src.cstr());
    check(out && libc.strstr(out, "ktm-880-deep.ktm:259: error: more than 256 try blocks and defers"),
          "300 in one function: the error names the file and line of the 257th");
    if (out && !libc.strstr(out, "ktm-880-deep.ktm:259:")) { printf("  got: %s\n", out); }
    mem.free(out);
    src.free();

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
