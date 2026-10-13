import cstr, fs, libc, proc, test;

/* -Memsafe names the stdlib function the program called (v0.18.0):
 * "via vec.Vec<String>.free", not a private helper of it such as
 * vec.vecItemFree, which it used to show when the call was inlined
 * into the program's function. */

#define SRC_FILE "__725_src.ktm"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what, u8 *out) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s: [%s]\n", what, out); }
}

u8 *memsafe(u8 *src) {
    fs.writeAll(SRC_FILE, cstr.view(src));
    return proc.shellOutput(KTM "-Memsafe -jit " SRC_FILE " 2>&1").take();
}

i32 main() {
    printf("Test - memsafe names the public stdlib call: ");

    u8 *out = memsafe("import str, vec;\ni32 main() {\n    vec.Vec<String> v = vec.new<String>();\n"
                      "    v.push(str.from(\"x\"));\n    String s = v.get(0);\n    v.free();\n"
                      "    s.free();\n    return 0;\n}\n");
    check(out && libc.strstr(out, "double free") &&
          libc.strstr(out, "first freed at " SRC_FILE ":6 in main, via vec.Vec<String>.free") &&
          libc.strstr(out, "this free   at " SRC_FILE ":7 in main, via String.free") &&
          !libc.strstr(out, "vecItemFree"),
          "a String freed by Vec.free, then again", out);

    out = memsafe("import vec;\ni32 main() {\n    vec.Vec<i64> v = vec.new<i64>();\n"
                  "    v.push(1);\n    return 0;\n}\n");
    check(out && libc.strstr(out, "never freed") &&
          libc.strstr(out, SRC_FILE ":3 in main, via vec.new<i64>"),
          "a Vec never freed: where it was made", out);
    fs.remove(SRC_FILE);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
