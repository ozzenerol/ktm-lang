import fs, libc, proc, test;

/* -Memsafe names where a block came from as file:line in the program's
 * own code (v0.18.0): a module's file, not main's; the module global's
 * initialiser as "<module>'s globals"; and the stdlib function that
 * allocated it ("via str.from"), not that function's insides. */

#define DIR "__675_prog"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what, u8 *out) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s: [%s]\n", what, out); }
}

i32 main() {
    printf("Test - memsafe report sites: ");

    proc.shell("rm -rf " DIR " && mkdir -p " DIR "/shop");
    fs.writeAll(DIR "/shop/shop.ktm",
        "module shop;\n"
        "import str, vec;\n"
        "\n"
        "vec.Vec<String> gItems = vec.new<String>();\n"
        "pub void reset() { gItems = vec.new<String>(); }\n"
        "pub String make(u8[] s)\n"
        "{\n"
        "    return str.from(s);\n"
        "}\n");
    fs.writeAll(DIR "/main.ktm",
        "import shop, mem;\n"
        "\n"
        "void main()\n"
        "{\n"
        "    shop.reset();\n"
        "    mem.alloc(40);\n"
        "    shop.make(\"apple\");\n"
        "}\n");
    u8 *out = proc.shellOutput("cd " DIR " && " KTM "-Memsafe -jit main.ktm 2>&1").take();

    check(out && libc.strstr(out, "memsafe: 3 allocation(s)"), "three leaks", out);
    check(out && libc.strstr(out, "at shop/shop.ktm:4 in shop's globals, via vec.new<String>"),
          "a module global, through a generic", out);
    check(out && libc.strstr(out, "at shop/shop.ktm:8 in shop.make, via str.from"),
          "a module function, through the stdlib", out);
    check(out && libc.strstr(out, "40 bytes  at main.ktm:6 in main\n") &&
          libc.strstr(out, "memsafe: 1 allocation(s) (192 bytes) still in use at exit"),
          "mem.alloc in main: no via", out);
    check(out && !libc.strstr(out, "`"),
          "no raw offsets", out);
    proc.shell("rm -rf " DIR);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
