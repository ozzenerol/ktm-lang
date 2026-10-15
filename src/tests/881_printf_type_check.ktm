import cstr, fs, libc, mem, proc, test;

/* v0.23.2: a literal format's values are checked against their
 * conversions, not only counted: `%s` of a number, `%d` of text or a
 * float, `%f` of an integer and a `*` width that isn't an integer are
 * errors at the value. printf, str.format, os.eprintf, String.appendFormat,
 * log.info, throw and a program's own `fmt, ...args` function alike.
 * What C accepts stays accepted: any integer (bool, enum, u8) for %d,
 * a pointer for %x and %p, a void * for %s. */

#define PT_SRC "./ktm-881-fmt.ktm"
#define PT_OUT "./ktm-881-fmt.out"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

u8 *compile(u8 *source) {
    proc.shell("rm -f " PT_SRC " " PT_OUT);
    fs.writeAll(PT_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               "-S " PT_SRC " -o /dev/null > " PT_OUT " 2>&1");
    u8 *out = fs.readAll(PT_OUT).take();
    proc.shell("rm -f " PT_SRC " " PT_OUT);
    return out;
}

/* the body `body` in main: refused with want, or (want null) accepted */
void expect(u8 *imports, u8 *body, u8 *want, u8 *what) {
    u8 *src = cstr.format("%s\nenum Mood { calm, cross };\nvoid own(u8 *fmt, ...args) {}\n"
                          "i32 main() {\n%s\nreturn 0;\n}\n", imports, body);
    u8 *out = compile(src);
    bool ok = want ? out && libc.strstr(out, want) != null
                   : out && libc.strstr(out, "error") == null;
    check(ok, what);
    if (!ok) { printf("  %s\n  ktm said: %s\n", body, out); }
    mem.free(out);
    mem.free(src);
}

i32 main() {
    printf("Test - printf type check: ");
    expect("", "printf(\"%s\\n\", 42);", "`%s` in a `printf` format prints text", "%s of a number");
    expect("", "printf(\"%d\\n\", \"text\");", "`%d` in a `printf` format prints an integer", "%d of text");
    expect("", "printf(\"%d\\n\", 2.5);", "prints an integer, not `f64`", "%d of a float");
    expect("", "i64 n = 7; printf(\"%.2f\\n\", n);", "`%f` in a `printf` format prints a float", "%f of an integer");
    expect("", "printf(\"%*d\\n\", 2.0, 1);", "`*` in a `printf` format takes an integer", "* of a float");
    expect("struct P { i64 x; };", "P p; printf(\"%d\\n\", p);", "prints an integer, not `P`", "%d of a struct");
    expect("import str;", "String s = str.format(\"%s\", 1); s.free();", "in a `str.format` format", "str.format");
    expect("import os;", "os.eprintf(\"%d\\n\", \"x\");", "in a `os.eprintf` format", "os.eprintf");
    expect("import str;", "String s; s.appendFormat(\"%s\", 3); s.free();", "in a `String.appendFormat` format", "String.appendFormat");
    expect("import log;", "log.info(\"%s\", 5);", "prints text", "log.info");
    expect("", "throw(1, \"bad %s\", 9);", "prints text", "throw's message");
    expect("", "own(\"%s\", 4);", "prints text", "a program's own fmt, ...args function");

    /* what stays accepted */
    expect("import str;",
           "u8[] sl = \"s\"; String st = str.from(\"t\"); u8 *c = \"c\"; void *v = c; u8 buf[4] = \"b\";\n"
           "printf(\"%s %s %s %s %s %Q\\n\", sl, st, c, v, buf, \"q\"); st.free();",
           null, "text of every kind for %s");
    expect("",
           "bool b = true; u8 small = 3; i32 mid = 4; Mood m = Mood.cross; i64 *p = &mid;\n"
           "printf(\"%d %d %d %d %c %u %x %X %o %p %p\\n\", b, small, mid, m, 'a', p, p, mid, mid, p, 0);",
           null, "integers, bools, enums, pointers for %x/%u/%p");
    expect("", "printf(\"%f %.1e %g %5.2f\\n\", 1.5, 2.5, 3.5, 4.0);", null, "floats");
    expect("", "printf(\"%*d|%-*s|%.*f\\n\", 5, 1, 3, \"ab\", 2, 1.5);", null, "* widths and precisions");
    expect("", "u8 *f = \"%d\\n\"; printf(f, \"text\");", null, "a format that isn't a literal isn't checked");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
