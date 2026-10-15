import cstr, fs, libc, proc, test;

/* `%s` prints a u8[] (v0.3.0 step 3): in a literal format of printf,
 * cstr.format, cstr.formatTo, cstr.appendFormat or throw, a slice passed to
 * `%s` is passed as its length and pointer and the `%s` becomes `%.*s`,
 * since a slice has no NUL. Width and `-` still apply; an argument that
 * isn't a variable is evaluated once. A slice with a computed format, a
 * precision, or another conversion is an error. */

#define SRC_FILE "__474_src.ktm"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 gCalls = 0;
u8[] counted() { gCalls++; return "once"; }
u8[] name() { return "world"; }

u8 *run(u8 *body) {
    u8 *src = cstr.format("i32 main() {\n    u8[] s = \"abc\";\n    %s\n    return 0;\n}\n", body);
    fs.writeAll(SRC_FILE, cstr.view(src));
    return proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                            "-jit " SRC_FILE " 2>&1").take();
}

i32 main() {
    printf("Test - %%s of a slice: ");
    u8[] s = "hello there";
    u8[] w = s[6:];
    check(!cstr.cmp(cstr.format("[%s] [%s]", s, w), "[hello there] [there]"), "cstr.format");
    check(!cstr.cmp(cstr.format("[%5s|%-7s|%s]", s[:2], name(), "lit"), "[   he|world  |lit]"),
          "width, left, a call, a literal");
    check(!cstr.cmp(cstr.format("%s %s %d", s[0:5], counted(), gCalls), "hello once 1") && gCalls == 1,
          "evaluated once");
    u8 buf[64];
    cstr.formatTo(buf, "%s!", w);
    check(!cstr.cmp(buf, "there!"), "cstr.formatTo");
    u8 *grown = cstr.appendFormat(null, "%s+%s", w, w);
    check(!cstr.cmp(grown, "there+there"), "cstr.appendFormat");
    try {
        throw(7, "bad %s here", w);
    } catch (Error e) {
        check(!cstr.cmp(e.message, "bad there here"), "throw");
    }
    check(!cstr.cmp(cstr.format("%%s %s", w), "%s there"), "%% left alone");

    u8 *out = run("printf(\"[%s|%4s]\", s, s[1:]);");
    check(out && !cstr.cmp(out, "[abc|  bc]"), "printf");
    out = run("u8 *f = \"%s\";\n    printf(f, s);");
    check(out && libc.strstr(out, "error: a slice or a String passed to `printf` needs a literal format"),
          "a computed format");
    out = run("printf(\"%d\", s);");
    check(out && libc.strstr(out, "error: `%d` in a `printf` format prints an integer, not `u8[]`"),
          "another conversion");
    out = run("printf(\"%.2s\", s);");
    check(out && libc.strstr(out, "can't have a precision"), "a precision");
    out = run("printf(\"%s %s\", s);");
    check(out && libc.strstr(out, "expects 2 arguments but 1 was supplied"), "counted as written");
    fs.remove(SRC_FILE);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
