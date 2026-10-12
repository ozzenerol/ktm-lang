import cstr, fs, libc, proc, str, test;

/* `pub` or `private` on an enum, struct or union belongs to it alone. It
 * used to stay set after the type's `;`, so the next declaration of the
 * module became pub too (a private function callable from outside), and
 * after `private enum ...;` a `pub` declaration was an error. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

void put(u8 *rel, u8 *text) {
    String path = str.format("__592_pub/%s", rel);
    fs.writeAll(path, cstr.view(text));
    path.free();
}

/* main.ktm in __592_pub: built and run, its output; or ktm's messages */
bool runs(u8 *main, u8 *want) {
    put("main.ktm", main);
    i64 res = proc.shell("cd __592_pub && ../../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                         "main.ktm -o main.bin > out.txt 2>&1 && ./main.bin > out.txt 2>&1");
    String out = fs.readAll("__592_pub/out.txt");
    bool ok = out.ptr && libc.strstr(out.ptr, want) != null;
    if (!ok && out.ptr) { printf("\n%s", out); }
    out.free();
    return ok;
}

i32 main() {
    printf("Test - pub on a type doesn't reach the next declaration: ");

    fs.remove("__592_pub", fs.REMOVE_RECURSE | fs.REMOVE_FORCE);
    fs.makeDir("__592_pub/m",, true);
    put("m/m.ktm",
        "module m;\n"
        "pub enum Color { red, green };\n"
        "i64 afterEnum() { return 1; }\n"
        "pub struct Box { i64 a; };\n"
        "i64 gAfterStruct = 2;\n"
        "pub union Num { i64 i; f64 f; };\n"
        "struct AfterUnion { i64 a; };\n"
        "private enum Mode { on };\n"
        "pub i64 open() { return 5; }\n");

    check(runs("import m;\ni32 main() { printf(\"%d\\n\", m.afterEnum()); return 0; }\n",
               "`afterEnum` is private to module m"), "a function after a pub enum stays private");
    check(runs("import m;\ni32 main() { printf(\"%d\\n\", m.gAfterStruct); return 0; }\n",
               "`gAfterStruct` is private to module m"), "a global after a pub struct stays private");
    check(runs("import m;\ni32 main() { m.AfterUnion u; return 0; }\n",
               "`AfterUnion` is private to module m"), "a struct after a pub union stays private");
    check(runs("import m;\n"
               "i32 main() {\n"
               "    m.Color c = m.Color.green;\n"
               "    m.Box b = {3};\n"
               "    m.Num n;\n"
               "    n.i = 4;\n"
               "    printf(\"%d %d %d %d\\n\", c, b.a, n.i, m.open());\n"
               "    return 0;\n"
               "}\n", "1 3 4 5"), "the pub types and the pub function after a private enum work");

    fs.remove("__592_pub", fs.REMOVE_RECURSE);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
