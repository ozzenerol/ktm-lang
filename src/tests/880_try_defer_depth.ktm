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

/* 60 ad Strings, then 12 tries one inside the other */
i64 deep() {
    ad String s0 = str.from("x");
    ad String s1 = str.from("x");
    ad String s2 = str.from("x");
    ad String s3 = str.from("x");
    ad String s4 = str.from("x");
    ad String s5 = str.from("x");
    ad String s6 = str.from("x");
    ad String s7 = str.from("x");
    ad String s8 = str.from("x");
    ad String s9 = str.from("x");
    ad String s10 = str.from("x");
    ad String s11 = str.from("x");
    ad String s12 = str.from("x");
    ad String s13 = str.from("x");
    ad String s14 = str.from("x");
    ad String s15 = str.from("x");
    ad String s16 = str.from("x");
    ad String s17 = str.from("x");
    ad String s18 = str.from("x");
    ad String s19 = str.from("x");
    ad String s20 = str.from("x");
    ad String s21 = str.from("x");
    ad String s22 = str.from("x");
    ad String s23 = str.from("x");
    ad String s24 = str.from("x");
    ad String s25 = str.from("x");
    ad String s26 = str.from("x");
    ad String s27 = str.from("x");
    ad String s28 = str.from("x");
    ad String s29 = str.from("x");
    ad String s30 = str.from("x");
    ad String s31 = str.from("x");
    ad String s32 = str.from("x");
    ad String s33 = str.from("x");
    ad String s34 = str.from("x");
    ad String s35 = str.from("x");
    ad String s36 = str.from("x");
    ad String s37 = str.from("x");
    ad String s38 = str.from("x");
    ad String s39 = str.from("x");
    ad String s40 = str.from("x");
    ad String s41 = str.from("x");
    ad String s42 = str.from("x");
    ad String s43 = str.from("x");
    ad String s44 = str.from("x");
    ad String s45 = str.from("x");
    ad String s46 = str.from("x");
    ad String s47 = str.from("x");
    ad String s48 = str.from("x");
    ad String s49 = str.from("x");
    ad String s50 = str.from("x");
    ad String s51 = str.from("x");
    ad String s52 = str.from("x");
    ad String s53 = str.from("x");
    ad String s54 = str.from("x");
    ad String s55 = str.from("x");
    ad String s56 = str.from("x");
    ad String s57 = str.from("x");
    ad String s58 = str.from("x");
    ad String s59 = str.from("x");
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
