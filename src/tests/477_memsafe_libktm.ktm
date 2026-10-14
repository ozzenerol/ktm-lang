import cstr, fs, libc, proc, str, test;

/* -Memsafe and the REPL see the library's own allocations (v0.3.0 step
 * 4b): mem.alloc/free/allocZero/realloc call the checker's hooks when it
 * sets them, so a String grown inside libktm is tracked. A double free
 * through a copy (`String b = a; a.free(); b.free();`) is reported with
 * where it was allocated and freed, a String never freed is a leak, and
 * a program that frees what it should gets no report. */

#define SRC_FILE "__477_src.ktm"
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

u8 *repl(u8 *src) {
    fs.writeAll(SRC_FILE, cstr.view(src));
    return proc.shellOutput("HOME=. " KTM "-repl < " SRC_FILE " 2>&1").take();
}

i32 main() {
    printf("Test - memsafe sees the library's allocations: ");

    u8 *out = memsafe("import str;\ni32 main() {\n    String a = str.from(\"shared\");\n"
                      "    String b = a;\n    a.free();\n    b.free();\n    printf(\"end\");\n"
                      "    return 0;\n}\n");
    check(out && libc.strstr(out, "double free of") && libc.strstr(out, "allocated   at __477_src.ktm:3 in main, via str.from") &&
          libc.strstr(out, "first freed at __477_src.ktm:5 in main, via String.free") &&
          libc.strstr(out, "this free   at __477_src.ktm:6 in main, via String.free") && libc.strstr(out, "end"),
          "a double free through a copy", out);

    out = memsafe("import str;\ni32 main() {\n    String s = str.from(\"kept\");\n"
                  "    s.append(\" and grown\");\n    printf(\"%s\", s);\n    return 0;\n}\n");
    check(out && libc.strstr(out, "kept and grown") &&
          libc.strstr(out, "memsafe: 1 allocation(s)") && libc.strstr(out, "never freed"),
          "a String never freed", out);

    out = memsafe("import cstr, mem, str;\ni32 main() {\n    String s = str.from(\"ok\");\n"
                  "    defer s.free();\n    s.append(\"!\");\n    u8 *t = cstr.format(\"%d\", 5);\n"
                  "    mem.free(t);\n    printf(\"%s\", s);\n    return 0;\n}\n");
    check(out && !cstr.cmp(out, "ok!"), "everything freed: no report", out);

    out = repl("import str;\nString s = str.from(\"abc\");\nString c = s;\ns.free();\nc.free();\n");
    check(out && libc.strstr(out, "double free of"), "the REPL", out);
    out = repl("import str;\nString s = str.from(\"abc\");\ns.append(\"def\");\ns.free();\nreplHeap();\n");
    check(out && libc.strstr(out, "heap: 0 live allocation(s), 0 bytes"), "the REPL's heap", out);
    fs.remove(SRC_FILE);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
