import cstr, fs, libc, mem, proc, test;

/* v0.23.2: the smallest i64 divided by -1 (the quotient doesn't fit; `%`
 * divides the same way) crashed on x86_64 with a report that said
 * "integer division by zero", and gave the dividend (and 0) on arm64,
 * where the program went on. It now crashes on both with "integer
 * overflow in division", and the exit status of a SIGFPE (136),
 * compiled and in the JIT. Other divisions by -1, and unsigned ones,
 * don't crash. */

#define DO_SRC "__892_divovf.ktm"
#define DO_BIN "./__892_divovf.out"
#define DO_ERR "__892_divovf.stderr"
#define DO_ST  "__892_divovf.status"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

/* main's body `body` (line 5 of the file is its first line) crashes
 * at line 5 + `at` with `what`, and the program dies of a SIGFPE */
bool crashes(u8 *body, i64 at, u8 *what, bool jit) {
    u8 *src = cstr.format("i64 gMinus = -1, gZero = 0;\n"
                          "i32 main()\n{\n    i64 low = -9223372036854775807 - 1;\n%s\n"
                          "    return 0;\n}\n", body);
    fs.writeAll(DO_SRC, cstr.view(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    mem.free(src);
    fs.remove(DO_ERR);
    fs.remove(DO_ST);
    if (jit) {
        proc.shell("(../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " DO_SRC
                   " 2> " DO_ERR "; echo $? > " DO_ST ") > /dev/null 2>&1");
    } else if (proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                          DO_SRC " -o " DO_BIN) == 0) {
        proc.shell("(" DO_BIN " 2> " DO_ERR "; echo $? > " DO_ST ") > /dev/null 2>&1");
    }
    u8 *err = fs.readAll(DO_ERR).take();
    u8 *st = fs.readAll(DO_ST).take();
    u8 *want = cstr.format("crash: %s\n  at " DO_SRC ":%d in main\n", what, 5 + at);
    bool ok = err && st && libc.strstr(err, want) && !cstr.cmpLen(st, "136\n", 4);
    if (!ok) { printf("\n%s: status %s stderr [%s]\n", jit ? "jit" : "aot", st, err); }
    mem.free(want);
    mem.free(err);
    mem.free(st);
    fs.remove(DO_SRC);
    fs.remove(DO_BIN);
    fs.remove(DO_ERR);
    fs.remove(DO_ST);
    return ok;
}

#define OVF "integer overflow in division (-9223372036854775808 / -1)"
#define ZERO "integer division by zero"

i64 gLow = -9223372036854775807 - 1, gMinusOne = -1;

i32 main() {
    printf("Test - integer division overflow crashes: ");
    i64 k;
    for (k = 0; k < 2; k++) {
        bool jit = k == 1;
        check(crashes("    printf(\"%d\\n\", low / gMinus);", 0, OVF, jit), "/ by a variable -1");
        check(crashes("    printf(\"%d\\n\", low % gMinus);", 0, OVF, jit), "% by a variable -1");
        check(crashes("    printf(\"%d\\n\", low / -1);", 0, OVF, jit), "/ by a constant -1");
        check(crashes("    i64 n = 5;\n    low /= n - 6;\n    printf(\"%d\\n\", low);", 1, OVF, jit),
              "/=");
        check(crashes("    printf(\"%d\\n\", low / gZero);", 0, ZERO, jit), "still by zero");
    }

    /* divisions that don't overflow */
    i64 five = -5;
    u64 high = 0x8000000000000000;
    check(five / gMinusOne == 5 && five % gMinusOne == 0, "another i64 by -1");
    check(gLow / 1 == gLow && gLow / -2 == 4611686018427387904 && gLow % -2 == 0,
          "the smallest i64 by something else");
    check(high / (u64)gMinusOne == 0 && high % (u64)gMinusOne == high, "unsigned");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
