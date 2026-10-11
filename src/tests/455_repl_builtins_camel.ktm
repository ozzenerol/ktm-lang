import cstr, fs, libc, proc, test;

/* The rest of the REPL's builtins in camelCase like replDisasm (were
 * ReplListGVars, ReplClassRep, ReplEval, ReplHeap and ReplReloadRc). */

#define IN_FILE "__455_in.ktm"

i32 main() {
    i64 correct = 0, tests = 5;
    printf("Test - REPL builtins replListGVars, replClassRep, replEval, replHeap, replReloadRc: ");

    u8 *src = "struct Pt455 { i64 xCoord; i64 yCoord; };\n"
              "i64 gCount455 = 3;\n"
              "replListGVars();\n"
              "replClassRep(\"Pt455\");\n"
              "replEval(\"printf(\\\"evald %d\\\\n\\\", 6 * 7);\");\n"
              "replHeap();\n"
              "replReloadRc();\n"
              "printf(\"end\\n\");\n";
    fs.writeAll(IN_FILE, src, cstr.len(src));
    /* HOME here, so no ~/.ktm_rc.ktm is read */
    u8 *out = proc.shellOutput("HOME=. $(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                               "-repl < " IN_FILE " 2>&1");
    if (out && libc.strstr(out, "gCount455")) { correct++; }
    else { printf("\n  replListGVars: [%s]\n", out); }
    if (out && libc.strstr(out, "Pt455") && libc.strstr(out, "yCoord")) { correct++; }
    else { printf("\n  replClassRep: [%s]\n", out); }
    if (out && libc.strstr(out, "evald 42")) { correct++; }
    else { printf("\n  replEval: [%s]\n", out); }
    if (out && libc.strstr(out, "heap: ")) { correct++; }
    else { printf("\n  replHeap: [%s]\n", out); }
    if (out && libc.strstr(out, "\nend") && !libc.strstr(out, "not defined")) { correct++; }
    else { printf("\n  replReloadRc: [%s]\n", out); }
    fs.remove(IN_FILE);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
