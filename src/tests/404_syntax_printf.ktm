import cstr, fs, libc, proc, test;

/* ktm syntax v0.2: printf(fmt, ...) is ktm's print, the same as the print
 * statement "fmt", ...; : %d/%x/%u print the full 64-bit value (narrow
 * integers are widened), a format using ktm's conversions (%Q %C)
 * goes through Print, and the arguments are checked against a literal
 * format at compile time. A snippet prints every case both ways; the two
 * outputs must match line for line. */

#define KTM "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

u8 *both =
"import date;\n\ni64 big = 5000000000;\n"
"i32 neg = -3;\n"
"u8 byte = 200;\n"
"i16 sh = -2;\n"
"f64 pi = 3.14159;\n"
"date.Date day;\n"
""
""
"void main()\n"
"{\n  day = 0;\n  printf(\"top level %d\\n\", 1);\n  printf(\"top level %d\\n\", 1);\n"
"  printf(\"%d %d %u %d\\n\", big, neg, byte, sh);\n"
"  printf(\"%d %d %u %d\\n\", big, neg, byte, sh);\n"
"  printf(\"%x %X %o\\n\", big, 255, 8);\n"
"  printf(\"%x %X %o\\n\", big, 255, 8);\n"
"  printf(\"%s|%5.2f|%c|%%\\n\", \"str\", pi, 'A');\n"
"  printf(\"%s|%5.2f|%c|%%\\n\", \"str\", pi, 'A');\n"
"  printf(\"%Q\\n\", \"a\\\"b\");\n"
"  printf(\"%Q\\n\", \"a\\\"b\");\n"
"  printf(\"no arguments\\n\");\n"
"  printf(\"no arguments\\n\");\n"
"  if (big > 0) { printf(\"in if %d\\n\", 2); }\n"
"  if (big > 0) { printf(\"in if %d\\n\", 2); }\n"
"  i64 n = printf(\"%d\\n\", 42);\n"
"  printf(\"count %d\\n\", n);\n"
"  printf(\"count %d\\n\", 3);\n"
"}\n";

/* Split s at each newline, in place: up to max lines into out. */
i64 linesFn(u8 *s, u8 **out, i64 max) {
    i64 n = 0;
    if (!s) { return 0; }
    while (*s && n < max) {
        out[n++] = s;
        while (*s && *s != '\n') { s++; }
        if (*s) { *s++ = 0; }
    }
    return n;
}

i32 main() {
    i64 correct = 0, tests = 0, i;
    printf("Test - printf: ");

    fs.writeAll("__404_both.ktm", cstr.view(both));
    u8 *out = proc.shellOutput(KTM "-jit __404_both.ktm 2>&1").take();
    u8 *aot = proc.shellOutput(KTM "__404_both.ktm -o __404_both 2>&1 && ./__404_both").take();

    tests++;   /* each printf line equals the print statement after it */
    i64 pairsOk = 1;
    u8 *copy = cstr.dup(out), *lines[32];
    i64 count = linesFn(copy, lines, 32);
    if (count == 17) {
        /* 7 pairs, then the 42 whose length printf returns, then 2 counts */
        for (i = 0; i < 14; i += 2) { if (cstr.cmp(lines[i], lines[i + 1])) { pairsOk = 0; } }
        if (cstr.cmp(lines[14], "42") || cstr.cmp(lines[15], "count 3") || cstr.cmp(lines[16], "count 3")) {
            pairsOk = 0;
        }
    } else {
        pairsOk = 0;
    }
    if (pairsOk) { correct++; }
    else { printf("\n  printf and the print statement differ (jit):\n%s\n", out); }

    tests++;   /* the 64-bit, narrow and Q lines have the right text */
    if (count == 17 && !cstr.cmp(lines[0], "top level 1") &&
            !cstr.cmp(lines[2], "5000000000 -3 200 -2") && !cstr.cmp(lines[4], "12a05f200 FF 10") &&
            !cstr.cmp(lines[6], "str| 3.14|A|%") && !cstr.cmp(lines[8], "a\\\"b")) { correct++; }
    else { printf("\n  printf output: [%s]\n", out); }

    tests++;   /* compiled, the same output */
    if (out && aot && !cstr.cmp(out, aot)) { correct++; }
    else { printf("\n  aot differs:\n%s\n", aot); }

    tests++;   /* the arguments are checked against the format */
    u8 *bad = "void main()\n{\n  printf(\"%s %d\\n\", \"x\");\n}\n";
    fs.writeAll("__404_bad.ktm", cstr.view(bad));
    u8 *err = proc.shellOutput(KTM "-jit __404_bad.ktm 2>&1").take();
    if (err && libc.strstr(err, "printf format expects 2 arguments but 1 was supplied") &&
            libc.strstr(err, "__404_bad.ktm:3:")) { correct++; }
    else { printf("\n  format check: [%s]\n", err); }

    proc.shell("rm -rf __404_*");   /* the files this test wrote */
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
