import fs, libc, proc, test;

/* A small function from another file, inlined into its caller, takes the
 * call's line (v0.18.0): its own line numbers were read against the
 * caller's file, so -Memsafe said main.ktm:<a line of the module>. */

#define DIR "__677_prog"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what, u8 *out) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s: [%s]\n", what, out); }
}

i32 main() {
    printf("Test - inlined code keeps the call's line: ");

    proc.shell("rm -rf " DIR " && mkdir -p " DIR "/pool");
    fs.writeAll(DIR "/pool/pool.ktm",
        "module pool;\n"
        "import mem;\n"
        "\n"
        "\n"
        "\n"
        "\n"
        "\n"
        "\n"
        "\n"
        "/* small enough to be inlined */\n"
        "pub u8 *grab(i64 n)\n"
        "{\n"
        "    return mem.alloc(n);\n"
        "}\n");
    fs.writeAll(DIR "/main.ktm",
        "import pool;\n"
        "\n"
        "void main()\n"
        "{\n"
        "    u8 *p = pool.grab(24);\n"
        "}\n");
    u8 *out = proc.shellOutput("cd " DIR " && " KTM "-Memsafe -jit main.ktm 2>&1").take();
    check(out && libc.strstr(out, "24 bytes  at main.ktm:5 in main"), "the call's line", out);
    check(out && !libc.strstr(out, "main.ktm:13"), "not the module's line", out);
    proc.shell("rm -rf " DIR);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
