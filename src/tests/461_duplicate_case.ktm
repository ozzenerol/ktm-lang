import cstr, fs, libc, proc, test;

/* Duplicate case labels: `case 1: case 1:` on an integer wasn't reported
 * (and printed a debug dump of the switch), an enum's said "Duplicate case
 * value: .L3" (a label), and a value inside an earlier range was missed.
 * Each is reported by its value, or its enum member, at its `case`. */

#define SRC_FILE "__461_src.ktm"

i32 main() {
    i64 correct = 0, tests = 5;
    printf("Test - duplicate case labels: ");

    u8 *src = "enum Color { red, green };\n"
              "i32 main() {\n"
              "    i64 x = 1;\n"
              "    switch (x) {\n"
              "        case 1: break;\n"
              "        case 1: break;\n"
              "    }\n"
              "    Color c = Color.red;\n"
              "    switch (c) {\n"
              "        case Color.red: break;\n"
              "        case Color.green: break;\n"
              "        case Color.red: break;\n"
              "    }\n"
              "    switch (x) {\n"
              "        case 1 ... 5: break;\n"
              "        case 7: break;\n"
              "        case 3: break;\n"
              "    }\n"
              "    return 0;\n"
              "}\n";
    fs.writeAll(SRC_FILE, cstr.view(src));
    u8 *out = proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                               "-jit " SRC_FILE " 2>&1");
    if (out && libc.strstr(out, "error: duplicate case value 1\n --> " SRC_FILE ":6:9")) { correct++; }
    else { printf("\n  int: [%s]\n", out); }
    if (out && libc.strstr(out, "error: duplicate case value Color.red\n --> " SRC_FILE ":12:9")) { correct++; }
    else { printf("\n  enum: [%s]\n", out); }
    if (out && libc.strstr(out, "error: duplicate case value 3\n --> " SRC_FILE ":17:9")) { correct++; }
    else { printf("\n  range: [%s]\n", out); }
    if (out && !libc.strstr(out, "<case>")) { correct++; }
    else { printf("\n  debug dump: [%s]\n", out); }

    /* distinct values are fine */
    u8 *ok = "i32 main() {\n    i64 x = 6;\n    switch (x) {\n        case 1 ... 5: break;\n"
             "        case 6: printf(\"six\"); break;\n        case 7: break;\n    }\n    return 0;\n}\n";
    fs.writeAll(SRC_FILE, cstr.view(ok));
    out = proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                           "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                           "-jit " SRC_FILE " 2>&1");
    if (out && !cstr.cmp(out, "six")) { correct++; }
    else { printf("\n  distinct: [%s]\n", out); }
    fs.remove(SRC_FILE);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
