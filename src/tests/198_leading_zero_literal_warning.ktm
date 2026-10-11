import cstr, fs, libc, mem, proc, test;

/* ktm has no octal literals: `0644` is the decimal number 644 (C
 * reads octal 420). That meaning stays, but it was silent; ktm now
 * warns at the literal when C would read a different number, and the
 * program still compiles and runs with the decimal value. */

#define LZ_FILE "__198_leading_zero.ktm"
#define LZ_OUT  "__198_leading_zero.out"
/* `timeout` is coreutils; without it the command just runs */
#define LZ_KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

void cleanupLeadingZeroTest(void) {
    fs.remove(LZ_FILE);
    fs.remove(LZ_OUT);
    fs.remove("./a.out");
}

i64 countWarnings(u8 *out) {
    i64 n = 0;
    u8 *p = out;
    while ((p = libc.strstr(p, "leading zero: "))) {
        n++;
        p++;
    }
    return n;
}

/* Run `source` under the JIT (with `flags`). It must exit with
 * `status`, print `nwarn` leading-zero diagnostics and contain each of
 * `want1` / `want2` (null to skip). */
bool runLeadingZero(u8 *flags, u8 *source, i64 status, i64 nwarn,
                                        u8 *want1, u8 *want2) {
    u8 *cmd, *out;
    i64 res;
    bool ok = true;

    cleanupLeadingZeroTest();
    if (!fs.writeAll(LZ_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    cmd = cstr.format(LZ_KTM "%s -jit " LZ_FILE " > " LZ_OUT " 2>&1", flags);
    res = proc.shell(cstr.view(cmd));
    mem.free(cmd);

    out = fs.readAll(LZ_OUT).take();
    if (!out) {
        cleanupLeadingZeroTest();
        return false;
    }
    if (res != status) { ok = false; }
    if (countWarnings(out) != nwarn) { ok = false; }
    if (want1 && !libc.strstr(out, want1)) { ok = false; }
    if (want2 && !libc.strstr(out, want2)) { ok = false; }
    if (!ok) { printf("exit status %d\n%s\n", res, out); }
    mem.free(out);
    cleanupLeadingZeroTest();
    return ok;
}

void main() {
    printf("Test - leading zero literal warning: ");
    i64 correct = 0, tests = 0;

    /* The warning, at the literal, and the value is still decimal */
    tests++;
    if (runLeadingZero("",
"void main() {
  i64 mode = 0644;
  printf(\"value=%d\\n\", mode);
}
", 0, 1,
            "warning: leading zero: 0644 is the decimal number 644 (ktm has no octal literals); use 0x1A4 for octal 0644",
            "value=644")) { correct++; }

    /* Pointed at the literal: line 2, column 14 */
    tests++;
    if (runLeadingZero("",
"void main() {
  i64 mode = 0644;
}
", 0, 1, ":2:14", null)) { correct++; }

    /* No warning where C and ktm agree or it is not an integer
     * literal with a leading zero */
    tests++;
    if (runLeadingZero("",
"void main() {
  i64 a = 0, b = 0x1A4, c = 0b101, d = 07, e = 08, f = 00, g = 0X10;
  f64 h = 0.5, i = 00.5, j = 0644.0, k = 0e3, m = 0644e1;
  printf(\"%d %d %d %d %d %d %d %.1f %.1f %.1f %.1f %.1f\\n\",
    a, b, c, d, e, f, g, h, i, j, k, m);
}
", 0, 0, "0 420 5 7 8 0 16 0.5 0.5 644.0 0.0 6440.0", null)) { correct++; }

    /* Each literal is reported once, a macro at its definition; the
     * U suffix is allowed */
    tests++;
    if (runLeadingZero("",
"const i64 PERM = 0755;
void main() {
  i64 a = 010U, b = 0777;
  printf(\"%d %d %d %d\\n\", PERM, PERM, a, b);
}
", 0, 3, "use 0x8 for octal 010", "755 755 10 777")) { correct++; }

    /* Nothing from a skipped #if group */
    tests++;
    if (runLeadingZero("",
"void main() {
#if 0
  i64 a = 0644;
#endif
  printf(\"ok\\n\");
}
", 0, 0, "ok", null)) { correct++; }

    /* An error with -Werror */
    tests++;
    if (runLeadingZero("-Werror",
"void main() {
  i64 mode = 0644;
}
", 1, 1, "rror: leading zero: 0644", null)) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
}
