#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* StrPrint with a conversion it doesn't know: a trailing '%' read past
 * the format's NUL; an unsupported conversion didn't take its argument,
 * so the later conversions got the wrong ones (`%ld %s` crashed; the
 * cases use letters that stay unsupported once %ld is), the
 * diagnostic named the next character, went to stdout, and the bad
 * character was dropped. Now the conversion is kept as written (as C
 * prints it), its argument is taken, and the diagnostic names it on
 * stderr. */

#define FB_SRC "./ktm-245-fb.ktm"
#define FB_OUT "./ktm-245-fb.out"
#define FB_ERR "./ktm-245-fb.err"

bool is(u8 *got, u8 *want) {
    bool ok = got && !str.cmp(got, want);
    if (!ok) { printf("got [%s] want [%s]\n", got, want); }
    mem.free(got);
    return ok;
}

i32 main() {
    printf("Test - StrPrint invalid conversions: ");
    i64 correct = 0, tests = 0;

    /* A '%' at the end stops at the NUL */
    u8 fmt[32];
    mem.set(fmt, 0, 32);
    str.copy(fmt, "100%");
    str.copy(fmt + 5, "LEAKED");
    tests++;
    if (is(str.formatTo(null, fmt), "100%")) { correct++; }
    tests++;
    if (is(str.formatTo(null, "x%-5"), "x%-5")) { correct++; }

    /* Unknown conversions are kept and take their argument */
    tests++;
    if (is(str.formatTo(null, "a%yb"), "a%yb")) { correct++; }
    tests++;
    u8 *wf = "%w %s"; /* a variable: the compile-time check counts C's conversions */
    if (is(str.formatTo(null, wf, 5, "five"), "%w five")) { correct++; }
    tests++;
    /* (a variable: the compile-time format check counts C's conversions) */
    u8 *f = "%y|%d|%s";
    if (is(str.formatTo(null, f, 1, 2, "three"), "%y|2|three")) { correct++; }
    /* A character that isn't a letter ends no conversion: no argument */
    tests++;
    if (is(str.formatTo(null, "%! %d", 3), "%! 3")) { correct++; }
    /* Known conversions are unchanged */
    tests++;
    if (is(str.formatTo(null, "%d%%%s|%5d|%-3x|", 7, "s", 42, 255), "7%s|   42|ff |")) { correct++; }

    /* The diagnostic goes to stderr and names the right character */
    tests++;
    u8 *prog = "import mem, str;\n\nvoid main() { u8 *s = str.formatTo(null, \"%k-%d-%\", 1, 2); "
                         "printf(\"[%s]\\n\", s); mem.free(s); }\n";
    proc.shell("rm -f " FB_SRC " " FB_OUT " " FB_ERR);
    fs.writeAll(FB_SRC, prog, str.len(prog), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-jit " FB_SRC " > " FB_OUT " 2> " FB_ERR);
    u8 *out = fs.readAll(FB_OUT);
    u8 *err = fs.readAll(FB_ERR);
    if (out && err && !str.cmp(out, "[%k-2-%]\n") &&
            libc.strstr(err, "Invalid format char: 'k'") &&
            libc.strstr(err, "'%' with no conversion at the end")) { correct++; }
    else { printf("out [%s] err [%s]\n", out, err); }
    mem.free(out);
    mem.free(err);
    proc.shell("rm -f " FB_SRC " " FB_OUT " " FB_ERR);

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
