import fs, libc, mem, proc, str, test;

/* The compile-time check of a str.formatTo-family format string against its
 * arguments only knew C's conversions: `str.formatTo(null, "%Q %d", "a", 1)`
 * was an error ("expects 1 argument but 2"), as were %C %p, and
 * an unknown conversion was counted together with the next one. It now
 * follows str.formatTo: flags `-0+ #`, `*` and `.*` taking an argument, length
 * modifiers, and %d %i %u %o %x %X %c %s %f %e %g %p %Q %C %%; an
 * unknown conversion is a warning and takes no argument. The error was
 * also placed at `file:0` when the format was not the first or second
 * argument (str.appendFormatLen, str.formatToSize); it is now at the format string. */

#define FMT_SRC "./ktm-261-fmt.ktm"
#define FMT_OUT "./ktm-261-fmt.out"

/* Compile `source` and return what ktm printed */
u8 *diagnose(u8 *source) {
    proc.shell("rm -f " FMT_SRC " " FMT_OUT);
    fs.writeAll(FMT_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " FMT_SRC " -o /dev/null > " FMT_OUT " 2>&1");
    u8 *out = fs.readAll(FMT_OUT);
    proc.shell("rm -f " FMT_SRC " " FMT_OUT);
    return out;
}

i64 count(u8 *s, u8 *what) {
    i64 n = 0;
    while (s && (s = libc.strstr(s, what))) {
        n++;
        s++;
    }
    return n;
}

/* ktm prints `errors` errors, `warnings` warnings, and `want` */
bool prints(u8 *source, i64 errors, i64 warnings, u8 *want) {
    u8 *out = diagnose(source);
    bool ok = out && count(out, "error:") == errors &&
                        count(out, "warning:") == warnings && libc.strstr(out, want);
    if (!ok) { printf("got: '%s'\n", out); }
    mem.free(out);
    return ok;
}

#define MAIN "import str;\nvoid main() {\n  u8 *s = null; i64 n = 0; i64 a[4];\n"
#define END "}\n"

i32 main() {
    printf("Test - format check of ktm conversions: ");
    i64 tests = 0;
    i64 correct = 0;

    tests++;
    if (prints(MAIN "  s = str.formatTo(null, \"%Q %d\", \"a\", 1);\n" END, 0, 0, "")) {
        correct++;
    } else { printf("Failed - %%Q\n"); }

    tests++;
    if (prints(MAIN "  s = str.formatTo(null, \"%C %p %% %Q\", 3, 4, \"q\");\n"
                         "  s = str.appendFormat(s, \"%i %u %o %x %X %c %s %f %e %g\", 1, 2, 3, 4, 5, 6, \"s\", 1.0, 2.0, 3.0);\n" END,
                         0, 0, "")) {
        correct++;
                         } else { printf("Failed - every conversion\n"); }

    tests++;
    if (prints(MAIN "  s = str.formatTo(null, \"%-08.3f|%+d|% d|%#x|%ld|%lld|%hhu|%zd|%jd|%td|%Lf|%qd|%5%\", "
                         "1.0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 1.5, 11);\n" END, 0, 0, "")) {
        correct++;
                         } else { printf("Failed - flags and length modifiers\n"); }

    tests++;
    if (prints(MAIN "  s = str.formatTo(null, \"%*d|%.*s|%-*.*f\", 5, 1, 2, \"abc\", 8, 2, 1.0);\n" END,
                         0, 0, "")) {
        correct++;
                         } else { printf("Failed - * and .*\n"); }

    tests++;
    if (prints(MAIN "  s = str.formatTo(null, \"%Q %d\", \"a\");\n" END, 1, 0,
                         "printf format expects 2 arguments but 1 was supplied\n"
                         " --> ./ktm-261-fmt.ktm:4:26\n")) {
        correct++;
                         } else { printf("Failed - too few\n"); }

    tests++;
    if (prints(MAIN "  s = str.formatTo(null, \"%*d\", 1);\n" END, 1, 0,
                         "printf format expects 2 arguments but 1 was supplied")) {
        correct++;
                         } else { printf("Failed - * needs its argument\n"); }

    tests++;
    if (prints(MAIN "  s = str.formatTo(null, \"%k %d\", 1);\n" END, 0, 1,
                         "Unknown format conversion `%k`")) {
        correct++;
                         } else { printf("Failed - unknown conversion\n"); }

    /* The format as the third argument, after a long one */
    tests++;
    if (prints(MAIN "  s = str.appendFormatLen(s, &a[(n + 1) * 2 - sizeof(a) / 16], \"%d %d\", 1);\n" END,
                         1, 0, " --> ./ktm-261-fmt.ktm:4:64\n")) {
        correct++;
                         } else { printf("Failed - str.appendFormatLen location\n"); }

    tests++;
    if (prints(MAIN "  s = str.appendFormat(s,\n    \"%d %s\", 1);\n" END, 1, 0,
                         " --> ./ktm-261-fmt.ktm:5:5\n")) {
        correct++;
                         } else { printf("Failed - format on the next line\n"); }

    /* str.formatToSize: the format is the third argument */
    tests++;
    if (prints(MAIN "  u8 b[16];\n  str.formatToSize(b, sizeof(b), \"%d %Q\", 1);\n" END, 1, 0,
                         " --> ./ktm-261-fmt.ktm:5:34\n")) {
        correct++;
                         } else { printf("Failed - str.formatToSize\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
