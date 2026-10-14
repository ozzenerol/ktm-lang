import cstr, fs, libc, proc, test;

/* A statement that is only a field, `self.port;` (v0.18.0): the compiler
 * stopped with "Unhandled Ast kind AST_CLASS_REF". It is now read and
 * dropped like `x;`, with a warning that it does nothing. A field of a
 * call's result is still evaluated (the call runs). */

#define SRC_FILE "__707_src.ktm"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what, u8 *out) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s: [%s]\n", what, out); }
}

i32 main() {
    printf("Test - a field on its own as a statement: ");
    fs.writeAll(SRC_FILE, cstr.view(
        "struct C { i64 port; i64 a[2]; };\n"
        "i64 gCalls = 0;\n"
        "C gC;\n"
        "C *get() { gCalls++; return &gC; }\n"
        "void C.init() { self.port; self.port = 5; self.a; }\n"
        "void main() {\n"
        "    C c;\n"
        "    c.init();\n"
        "    c.port;\n"
        "    get().port;\n"
        "    printf(\"port %d calls %d\\n\", c.port, gCalls);\n"
        "}\n"));
    u8 *out = proc.shellOutput(KTM "-jit " SRC_FILE " 2>&1").take();
    check(out && libc.strstr(out, "port 5 calls 1") && !libc.strstr(out, "Unhandled"), "runs, the call evaluated (JIT)", out);
    check(out && libc.strstr(out, "warning: `self.port` on its own does nothing") &&
          libc.strstr(out, "warning: `c.port` on its own does nothing"), "warned", out);
    out = proc.shellOutput(KTM "-run " SRC_FILE " 2>/dev/null").take();
    check(out && libc.strstr(out, "port 5 calls 1"), "runs (AOT)", out);
    fs.remove(SRC_FILE);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
