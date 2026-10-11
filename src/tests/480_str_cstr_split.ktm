import cstr, fs, libc, mem, proc, str, test;

/* str and cstr (v0.3.0 step 6a): str is String and u8[] text, cstr C's
 * NUL-terminated strings. str.format gives a String; cstr.format the old
 * u8 * (mem.free it). str's trims and splits are u8[] views (the u8 *
 * versions that wrote into the string are gone), str.trimEnd takes the
 * bytes to drop (whitespace by default), str.toLower/toUpper/isSpace take
 * a byte. A String's methods need no `import str`; `str.` does. */

#define SRC_FILE "__480_src.ktm"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

u8 *run(u8 *src) {
    fs.writeAll(SRC_FILE, src, cstr.len(src));
    return proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                            "-jit " SRC_FILE " 2>&1");
}

i32 main() {
    printf("Test - str and cstr: ");

    String s = str.format("%d-%s-%s", 7, "c", "u8"[0:1]);
    defer s.free();
    check(!cstr.cmp(typeof(s), "String") && s.equals("7-c-u") && s.ptr[s.len] == 0, "str.format is a String");
    s.appendFormat("+%s", s);
    check(s.equals("7-c-u+7-c-u"), "appendFormat of itself");

    u8 *c = cstr.format("%d|%s", 42, "x");
    check(!cstr.cmp(c, "42|x") && !cstr.cmp(typeof(c), "u8 *"), "cstr.format is a u8 *");
    mem.free(c);
    u8 buf[32];
    cstr.formatTo(buf, "%05d", 42);
    check(!cstr.cmp(buf, "00042") && cstr.len("abc") == 3 && cstr.cmpLen("abX", "abY", 2) == 0,
          "cstr.formatTo, len, cmpLen");

    u8[] before, after;
    check(str.splitFirst("k=v=w", "=", &before, &after) && before.equals("k") && after.equals("v=w"),
          "splitFirst");
    check(str.splitLast("a/b/c.ktm", "/", &before, &after) && before.equals("a/b") &&
          after.equals("c.ktm"), "splitLast");
    check(!str.splitFirst("none", "/", &before, &after) && before.equals("none") && after.len == 0,
          "splitFirst without the separator");
    check(str.trimStart("  x ").equals("x ") && str.trimEnd("  x \r\n").equals("  x") &&
          str.trimEnd("x..!!", "!.").equals("x"), "trimStart, trimEnd");
    check(str.toUpper('q') == 'Q' && str.toLower('Q') == 'q' && str.isSpace('\t') && !str.isSpace('x'),
          "byte functions");

    u8 *out = run("i32 main() {\n    String s;\n    defer s.free();\n    s.append(\"no import\");\n"
                  "    printf(\"%s\", s);\n    return 0;\n}\n");
    check(out && !cstr.cmp(out, "no import"), "String methods without import str");
    out = run("i32 main() {\n    return str.len(\"x\");\n}\n");
    check(out && libc.strstr(out, "error: Variable or function `str` has not been defined"),
          "str. needs its import");
    out = run("import str;\ni32 main() {\n    return str.cmp(\"a\", \"b\");\n}\n");
    check(out && libc.strstr(out, "str.cmp"), "the C functions are cstr's");
    fs.remove(SRC_FILE);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
