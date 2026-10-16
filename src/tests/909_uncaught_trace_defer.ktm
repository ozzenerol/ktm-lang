import fs, proc, str, test;

/* v0.28.1: an uncaught error that passed through a defer is traced from
 * where it was thrown. The trace started at the function whose defer
 * rethrew it, and left out the calls below (main.ktm:11 for a throw deep
 * in a package). */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 main() {
    printf("Test - an uncaught error's trace through defers: ");

    fs.writeAll("__909.ktm",
        "void boom(i64 i) {\n"
        "    u8[] s = \"abc\";\n"
        "    printf(\"%c\\n\", s[i]);\n"
        "}\n"
        "void deep() {\n"
        "    throw(3, \"deep\");\n"
        "}\n"
        "void mid(i64 how) {\n"
        "    defer printf(\"mid defer\\n\");\n"
        "    if (how == 0) { deep(); }\n"
        "    boom(9);\n"
        "}\n"
        "i32 main(i32 argc, u8 **argv) {\n"
        "    defer printf(\"main defer\\n\");\n"
        "    try { mid(0); } catch (Error e) { printf(\"caught %d\\n\", e.code); }\n"
        "    mid(argc > 1 ? 0 : 1);\n"
        "    return 0;\n"
        "}\n");
    scoped String built = proc.shellOutput("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                                           "-o __909 __909.ktm 2>&1");
    check(built.len == 0, "the program compiles");

    scoped String b = proc.shellOutput("./__909 2>&1");
    check(str.contains(b[0:], "caught 3"), "a caught throw is still caught");
    check(str.contains(b[0:], "caught 3\nmid defer\nmain defer\nuncaught error"), "the defers run first");
    check(str.contains(b[0:], "at __909.ktm:3 in boom"), "a bounds error: traced from its line");
    check(str.contains(b[0:], "called from __909.ktm:11 in mid") &&
          str.contains(b[0:], "called from __909.ktm:16 in main"), "and the calls up to main");

    scoped String t = proc.shellOutput("./__909 again 2>&1");
    check(str.contains(t[0:], "at __909.ktm:6 in deep") &&
          str.contains(t[0:], "called from __909.ktm:10 in mid"), "a throw: traced from its line");
    if (!str.contains(t[0:], "at __909.ktm:6 in deep")) {
        printf("  got: [%.*s]\n", t.len, t.ptr);
    }

    fs.remove("__909.ktm");
    fs.remove("__909");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
