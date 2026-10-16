import fs, proc, str, test;

/* v0.28.1: a default value is checked against its parameter as an
 * argument is. `String path = ".history"` compiled and handed the
 * function a garbage String (len ~1e14); it is an error now, a pointer
 * or integer mix-up a warning, and the defaults that fit still work. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* compiles src, the compiler's messages */
String compile(u8[] src) {
    fs.writeAll("__908.ktm", src);
    String out = proc.shellOutput("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                                  "-o __908 __908.ktm 2>&1");
    fs.remove("__908.ktm");
    fs.remove("__908");
    return out;
}

enum Color { red, green };

i64 fits(u8[] s = "ab", u8 *p = "c", f64 t = 3, bool b = true, Color c = Color.green,
         void *q = null, i32 n = -1) {
    return s.len + (p[0] == 'c') + (i64)t + b + (i64)c + (q == null) + n;
}

i32 main() {
    printf("Test - default values fit their parameters: ");

    check(fits() == 2 + 1 + 3 + 1 + 1 + 1 - 1, "defaults that fit");

    scoped String a = compile("void f(String path = \".history\") { }\n"
                              "i32 main() { f(); return 0; }\n");
    check(str.contains(a[0:], "default value of parameter `path` is a `u8[9]`, not a `String`"),
          "a literal for a String is an error");
    check(str.contains(a[0:], "error"), "and stops the build");

    scoped String b = compile("void f(i64 *p = 5) { }\n"
                              "i32 main() { f(); return 0; }\n");
    check(str.contains(b[0:], "warning: default value of parameter `p` is a `i64`, not a `i64 *`"),
          "an integer for a pointer warns");

    scoped String c = compile("struct P { i64 x; };\n"
                              "void f(P p = 1) { }\n"
                              "i32 main() { f(); return 0; }\n");
    check(str.contains(c[0:], "default value of parameter `p` is a `i64`, not a `P`"),
          "a number for a struct is an error");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
