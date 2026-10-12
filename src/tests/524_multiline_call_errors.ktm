import cstr, fs, libc, mem, proc, test;

/* Errors inside calls that span several lines point at the line of the
 * argument (or the call) at fault, and lines after them stay right: once
 * a call over lines with joined string literals, or a macro string, put
 * the error on a later line. */

#define AT_SRC "./ktm-524-lines.ktm"
#define AT_OUT "./ktm-524-lines.out"

i64 gCorrect = 0, gTests = 0;

void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

u8 *gOut;

void compile(u8 *source) {
    proc.shell("rm -f " AT_SRC " " AT_OUT);
    fs.writeAll(AT_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " AT_SRC " -o /dev/null > " AT_OUT " 2>&1");
    gOut = fs.readAll(AT_OUT).take();
    proc.shell("rm -f " AT_SRC " " AT_OUT);
}

/* an error whose message has `what` is reported at line:col (any of the
 * errors with that message) */
bool at(u8 *what, u8 *where) {
    for (u8 *e = gOut; e && (e = libc.strstr(e, what)); e++) {
        u8 *arrow = libc.strstr(e, "-->");
        u8 *line = arrow ? libc.strstr(arrow, where) : null;
        u8 *eol = arrow ? libc.strchr(arrow, '\n') : null;
        if (line && eol && line < eol) { return true; }
    }
    return false;
}

i32 main() {
    printf("Test - error lines in multi-line calls: ");
    compile(
"import proc, str;
#define CMD \"echo \" \\
    \"hi\"
i64 take3(u8[] a, i64 b, u8[] c) { return a.len + b + c.len; }
void main()
{
    u8 *x = proc.shellOutput(\"a\"
        \"b\");
    String b = proc.shellOutput(CMD
        \" there\");
    u8 *p = \"x\";
    i64 r = take3(p,
                  1,
                  \"c\");
    i64 s = take3(\"a\",
                  1,
                  p);
    i64 t = take3(\"a\", 1,
                  \"c\") + take3(p, 2, \"d\");
    String q = str.format(\"%d %d\",
                          1);
    undefinedHere(1,
        2);
}
");
    check(at("operator cannot be applied", ":7:13"), "a bad result of a call over 2 lines: its first line");
    check(at("expected 'u8[]' got 'u8 *'", ":12:19"), "the bad first argument");
    check(at("expected 'u8[]' got 'u8 *'", ":17:19"), "the bad last argument, on its own line");
    check(at("expected 'u8[]' got 'u8 *'", ":19:32"), "a second call on the closing line");
    check(at("printf format expects 2", ":20:27"), "a format error: the format's line");
    check(at("undefinedHere", ":22:5"), "lines after them are right");
    if (gCorrect != gTests) { printf("%s\n", gOut); }
    mem.free(gOut);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
