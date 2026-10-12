import fs, libc, proc, str, test;

/* An unknown name followed by another word (`foo bar;`, `foo i64 n;`)
 * is reported at the name. It used to rewind one token too far: as the
 * first statement of a block the error pointed at the block's `{`
 * ("Unexpected character `{` perhaps you meant ';' or ','?"), later on
 * at the statement before it. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* ktm on `body` inside `void f() { ... }`: fails, and its output has
 * `want` and the location `at`, and no `{` error */
bool reports(u8 *body, u8 *want, u8 *at) {
    String src = str.format("void f() {\n    %s\n}\ni32 main() { return 0; }\n", body);
    fs.writeAll("__542_src.ktm", src);
    src.free();
    i64 res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                         "__542_src.ktm -o __542_src.bin > __542_src.out 2>&1");
    String out = fs.readAll("__542_src.out");
    bool ok = res != 0 && out.ptr && libc.strstr(out.ptr, want) != null &&
              libc.strstr(out.ptr, at) != null &&
              libc.strstr(out.ptr, "Unexpected character `{`") == null;
    if (!ok && out.ptr) { printf("\n%s", out); }
    out.free();
    fs.remove("__542_src.ktm");
    fs.remove("__542_src.out");
    fs.remove("__542_src.bin");
    return ok;
}

i32 main() {
    printf("Test - an unknown name before another word: ");

    check(reports("foo bar;", "Type `foo` has not been declared", "__542_src.ktm:2:5"),
          "first statement: foo bar;");
    check(reports("foo i64 n;", "Type `foo` has not been declared", "__542_src.ktm:2:5"),
          "first statement: foo i64 n;");
    check(reports("{ foo bar; }", "Type `foo` has not been declared", "__542_src.ktm:2:7"),
          "first statement of an inner block");
    check(reports("i64 x = 1; foo bar = 2;", "Type `foo` has not been declared", "__542_src.ktm:2:16"),
          "a later statement");
    check(reports("foo 5;", "Variable or function `foo` has not been defined", "__542_src.ktm:2:5"),
          "a number after it");
    check(reports("foo \"s\";", "Variable or function `foo` has not been defined", "__542_src.ktm:2:5"),
          "a string after it");
    check(reports("foo;", "Variable or function `foo` has not been defined", "__542_src.ktm:2:5"),
          "alone, as before");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
