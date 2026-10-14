import cstr, fs, libc, proc, test;

/* -Memsafe reports leaks when the program ends with exit() too, not only
 * when main returns (v0.18.0); once, and with the program's exit code
 * kept. A program that frees everything before exit() gets no report.
 * What a variable still holds when exit() runs is in use, not a leak
 * (one summary line); what nothing points to any more is a leak. */

#define SRC_FILE "__674_src.ktm"
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
    return proc.shellOutput(KTM "-Memsafe -jit " SRC_FILE " 2>&1; echo \" rc=$?\"").take();
}

i64 count(u8 *s, u8 *what) {
    i64 n = 0;
    while (s && (s = libc.strstr(s, what))) { n++; s++; }
    return n;
}

i32 main() {
    printf("Test - memsafe reports leaks on exit(): ");

    u8 *out = memsafe("import libc, str;\nvoid lose(u8[] t) {\n    String s = str.from(t);\n}\n"
                      "void main() {\n    lose(\"leaked\");\n    printf(\"end\");\n    libc.exit(3);\n}\n");
    check(out && libc.strstr(out, "end") && count(out, "memsafe: 1 allocation(s) (17 bytes) never freed") == 1 &&
          libc.strstr(out, ".ktm:3 in lose, via str.from") && libc.strstr(out, "rc=3"),
          "a String leaked before libc.exit", out);

    out = memsafe("import libc, str;\nString gKept;\nvoid main() {\n    gKept = str.from(\"global\");\n"
                  "    String s = str.from(\"held\");\n    libc.exit(0);\n}\n");
    check(out && !libc.strstr(out, "never freed") &&
          count(out, "memsafe: 2 allocation(s) (34 bytes) still in use at exit") == 1 &&
          libc.strstr(out, ".ktm:4 in main, via str.from") && libc.strstr(out, ".ktm:5 in main, via str.from"),
          "held by a global and a variable at exit: in use, not leaks, each listed", out);

    out = memsafe("import libc, str;\nvoid main() {\n    String s = str.from(\"freed\");\n"
                  "    s.free();\n    printf(\"end\");\n    libc.exit(0);\n}\n");
    check(out && !libc.strstr(out, "never freed") && libc.strstr(out, "rc=0"),
          "everything freed before exit: no report", out);

    out = memsafe("import str;\ni32 main() {\n    String s = str.from(\"leaked\");\n"
                  "    return 0;\n}\n");
    check(out && count(out, "never freed") == 1, "a return from main reports once", out);
    fs.remove(SRC_FILE);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
