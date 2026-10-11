import fs, libc, mem, proc, str, test;

/* Text in a skipped #if group reported errors: a malformed number
 * (`0b12`), a long or unterminated char const (an apostrophe in
 * `don't` ran on over the #endif), an unknown directive. Like C, a
 * skipped group is now only scanned for the directives that end it. */

#if 0
i64 x = 0b12;
i64 y = 1.2.3e;
i64 z = 0x;
i64 c = 'abcdefghij';
don't do this
#foo bar
#if 1
    nested 0b2 'too long for a char const'
  #bogus
#endif
#elif 0
it's skipped too, 0b9
#else
i64 gBranch = 3;
#endif

#ifdef NOT_DEFINED_174
#unknown_directive
i64 bad = 0b;
#endif

#if 1
i64 gLive = 1;
#else
we're not here 08x
#endif

#define SKIP_FILE "__174_skipped_if.ktm"
#define SKIP_OUT  "__174_skipped_if.out"

/* Code that is not skipped still reports the error */
bool liveCodeReports(u8 *source, u8 *expected) {
    u8 *out;
    i64 res;
    bool good = true;

    fs.remove(SKIP_FILE);
    if (!fs.writeAll(SKIP_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " SKIP_FILE " > " SKIP_OUT " 2>&1");
    out = fs.readAll(SKIP_OUT);
    if (res == 0 || !out || !libc.strstr(out, expected)) { good = false; }
    if (!good && out) { printf("%s\n", out); }
    if (out) { mem.free(out); }
    fs.remove(SKIP_FILE);
    fs.remove(SKIP_OUT);
    return good;
}

i32 main() {
    i64 correct = 0, tests = 0;

    printf("Test - skipped #if groups report nothing: ");

    tests++; if (gBranch == 3) { correct++; }
    tests++; if (gLive == 1) { correct++; }

    tests++;
    if (liveCodeReports("i64 x = 0b12;\nvoid main() {}\n",
                                            "malformed numeric literal")) { correct++; }
    tests++;
    if (liveCodeReports("#foo\nU0 Main() {}\n",
                                            "invalid preprocessor directive '#foo'")) { correct++; }
    tests++;
    if (liveCodeReports("i64 c = 'abcdefghij';\nvoid main() {}\n",
                                            "a character literal holds one character")) { correct++; }
    /* a skipped group still has to end */
    tests++;
    if (liveCodeReports("#if 0\ndon't\nU0 Main() {}\n", "Unterminated #if")) {
        correct++;
    }
    /* and #elif conditions read after skipped text are still checked */
    tests++;
    if (liveCodeReports("#if 0\n0b2\n#elif (1 +\n#endif\nU0 Main() {}\n",
                                            "#elif")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
