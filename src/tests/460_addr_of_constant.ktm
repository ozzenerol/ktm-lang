import fs, libc, proc, str, test;

/* `&5` / `&E.a` reached the code generator, which gave up with
 * "&AST_LITERAL not supported in slice" (and `&f()`, `&(x + 1)`,
 * `&"s"` the same way): a parse error at the `&` now. */

#define SRC_FILE "__460_src.ktm"

i64 gCorrect = 0, gTests = 0;

void expect(u8 *expr, u8 *want) {
    u8 *src = str.format("enum E { a, b };\ni64 f() { return 1; }\n"
                         "i32 main() {\n    i64 x = 1;\n    auto p = %s;\n    return 0;\n}\n", expr);
    fs.writeAll(SRC_FILE, src, str.len(src));
    u8 *out = proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                               "-jit " SRC_FILE " 2>&1");
    gTests++;
    bool ok = want ? out && libc.strstr(out, want) && libc.strstr(out, " --> " SRC_FILE ":5:14") &&
                     !libc.strstr(out, "not supported")
                   : out && !str.cmp(out, "");
    if (ok) { gCorrect++; }
    else { printf("\n  %s: [%s]\n", expr, out); }
}

i32 main() {
    printf("Test - the address of a constant: ");
    expect("&5", "error: can't take the address of a constant");
    expect("&E.a", "error: can't take the address of a constant");
    expect("&null", "error: can't take the address of a constant");
    expect("&f()", "error: can't take the address of a function call");
    expect("&(x + 1)", "error: can't take the address of a binary op");
    expect("&\"s\"", "error: can't take the address of a string literal");
    /* what has an address still does */
    expect("&x", null);
    expect("&f", null);
    fs.remove(SRC_FILE);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
