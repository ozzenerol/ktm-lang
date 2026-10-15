import cstr, fs, libc, mem, proc, test;

/* v0.23.2: an integer division (or %) by zero crashed with "integer
 * division by zero" on x86_64 but gave 0 on arm64 (SDIV and UDIV don't
 * trap), and the program went on. arm64 now tests the divisor and
 * crashes the same way, with the same report and exit status (SIGFPE,
 * 136), compiled and in the JIT. A constant divisor other than 0 is
 * not tested and divides as before. */

#define DZ_SRC "__891_divzero.ktm"
#define DZ_BIN "./__891_divzero.out"
#define DZ_ERR "__891_divzero.stderr"
#define DZ_ST  "__891_divzero.status"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

/* main's body `body` (line 6 of the file is its first line) prints
 * "before" and then divides by zero at line 6 + `at`: it must print
 * "before" and the crash report for that line, and die of a SIGFPE */
bool crashes(u8 *body, i64 at, bool jit) {
    u8 *src = cstr.format("i64 gZero = 0;\nu64 gUZero = 0;\n\n"
                          "i32 main()\n{\n%s\n    return 0;\n}\n", body);
    fs.writeAll(DZ_SRC, cstr.view(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    mem.free(src);
    fs.remove(DZ_ERR);
    fs.remove(DZ_ST);
    if (jit) {
        proc.shell("(../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " DZ_SRC
                   " 2> " DZ_ERR "; echo $? > " DZ_ST ") > /dev/null 2>&1");
    } else if (proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                          DZ_SRC " -o " DZ_BIN) == 0) {
        proc.shell("(" DZ_BIN " 2> " DZ_ERR "; echo $? > " DZ_ST ") > /dev/null 2>&1");
    }
    u8 *err = fs.readAll(DZ_ERR).take();
    u8 *st = fs.readAll(DZ_ST).take();
    u8 *want = cstr.format("crash: integer division by zero\n  at " DZ_SRC ":%d in main\n", 6 + at);
    bool ok = err && st && libc.strstr(err, want) && !cstr.cmpLen(st, "136\n", 4);
    if (!ok) { printf("\n%s: status %s stderr [%s]\n", jit ? "jit" : "aot", st, err); }
    mem.free(want);
    mem.free(err);
    mem.free(st);
    fs.remove(DZ_SRC);
    fs.remove(DZ_BIN);
    fs.remove(DZ_ERR);
    fs.remove(DZ_ST);
    return ok;
}

i64 gTen = 10, gZeroV = 0;

i32 main() {
    printf("Test - integer division by zero crashes: ");
    bool jit = false;
    i64 k;
    for (k = 0; k < 2; k++) {
        jit = k == 1;
        check(crashes("    i64 a = 10;\n    printf(\"%d\\n\", a / gZero);", 1, jit), "i64 /");
        check(crashes("    i64 a = 10;\n    printf(\"%d\\n\", a % gZero);", 1, jit), "i64 %");
        check(crashes("    u64 a = 10;\n    printf(\"%d\\n\", a / gUZero);", 1, jit), "u64 /");
        check(crashes("    u64 a = 10;\n    printf(\"%d\\n\", a % gUZero);", 1, jit), "u64 %");
        check(crashes("    i32 a = 10, z = (i32)gZero;\n    a /= z;\n    printf(\"%d\\n\", a);", 1, jit),
              "i32 /=");
        check(crashes("    u8 a = 10, z = (u8)gZero;\n    a %= z;\n    printf(\"%d\\n\", a);", 1, jit),
              "u8 %=");
    }

    /* divisions that don't divide by zero */
    i64 m7 = -7, two = 2;
    u64 big = 0xffffffffffffffff;
    check(gTen / 3 == 3 && gTen % 3 == 1, "a constant divisor");
    check(m7 / two == -3 && m7 % two == -1, "signed, by a variable");
    check(big / (u64)two == 0x7fffffffffffffff && big % (u64)two == 1, "unsigned, by a variable");
    check(gZeroV / gTen == 0 && gZeroV % gTen == 0, "0 divided");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
