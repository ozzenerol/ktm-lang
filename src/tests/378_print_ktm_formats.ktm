import test;

import fs, libc, mem, proc, str;

/* Print statements ("%Q\n", s;) went straight to C's printf, so the ktm
 * conversions %Q %D %T %C %F printed garbage or the letter, and the
 * compile-time argument check counted the format the C way. A literal
 * format that uses one of them now calls the library's Print, which
 * formats like StrPrint; other print statements still call printf. */

#define PF_FILE "__378_print.ktm"
#define PF_OUT  "__378_print.out"
#define PF_BIN  "__378_print.bin"
#define PF_DATA "__378_print.txt"

void cleanupPrintFmt(void) {
    fs.remove(PF_FILE);
    fs.remove(PF_OUT);
    fs.remove(PF_BIN);
    fs.remove(PF_DATA);
}

/* ktm's output (and the program's, piped) for `src`, built with `how`
 * ("-jit", or "-o" to compile a binary and run it); null if it can't be
 * written or read. *_res gets the exit status. */
u8 *runPrint(u8 *src, u8 *how, i64 *resArg) {
    u8 *cmd;
    cleanupPrintFmt();
    if (!fs.writeAll(PF_FILE, src, str.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return null;
    }
    if (!str.cmp(how, "-jit")) {
        cmd = "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " PF_FILE " > " PF_OUT " 2>&1";
    } else {
        cmd = "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " PF_FILE " -o " PF_BIN " > " PF_OUT " 2>&1 && ./" PF_BIN " > " PF_OUT " 2>&1";
    }
    *resArg = proc.shell(cmd);
    return fs.readAll(PF_OUT);
}

/* `src` runs (both ways) and prints exactly `want` */
bool prints(u8 *src, u8 *want) {
    u8 *out, *how[2] = {"-jit", "-o"};
    i64 res, i;
    bool ok = true;
    for (i = 0; i < 2; i++) {
        out = runPrint(src, how[i], &res);
        if (res != 0 || !out || str.cmp(out, want)) {
            ok = false;
            printf("\n  %s printed:\n%s\n  wanted:\n%s", how[i], out, want);
        }
        mem.free(out);
    }
    cleanupPrintFmt();
    return ok;
}

/* `src` fails to compile with `msg` */
bool rejects(u8 *src, u8 *msg) {
    i64 res;
    u8 *out = runPrint(src, "-jit", &res);
    bool ok = res != 0 && out && libc.strstr(out, msg) != null;
    if (!ok && out) { printf("\n%s\n", out); }
    mem.free(out);
    cleanupPrintFmt();
    return ok;
}

i32 main() {
    printf("Test - print statements with ktm formats: ");
    i64 correct = 0, tests = 0;

    /* %Q and %C, with widths, mixed with C conversions and plain print
     * statements: the output stays in order through a pipe */
    tests++;
    if (prints(
"void main()
{
  u8 *s = \"a\\\"b\\n\";
  printf(\"plain %d %s\\n\", 42, \"x\");
  printf(\"%Q|%C|%5s|%d\\n\", s, 'abc', \"hi\", -7);
  printf(\"plain %05.1f\\n\", 3.14159);
  printf(\"%c%C|%-4C|%3C|\\n\", 'x', 'y', 'ab', 'z');
}
",
"plain 42 x
a\\\"b\\n|ABC|   hi|-7
plain 003.1
xY|AB  |  Z|
")) { correct++; }

    /* %D %T print what StrPrint prints (the time changes from run to run,
     * so the two lines are compared with each other), %F a file's contents */
    tests++;
    {
        i64 res;
        u8 *out = runPrint(
"import date, fs, str;

void main()
{
  date.Date d = date.now();
  u8 *a = str.format(\"%D %T\", d, d);
  fs.writeAll(\"" PF_DATA "\", \"FILE\", 4);
  printf(\"%D %T\\n\", d, d);
  printf(\"%s\\n\", a);
  printf(\"[%F]\\n\", \"" PF_DATA "\");
}
", "-o", &res);
        u8 *nl = null;
        if (out) { nl = str.firstOf(out, "\n"); }
        if (res == 0 && nl && str.len(out) > 20 && !str.cmpLen(out, nl + 1, 19) &&
                !str.cmp(nl + 21, "[FILE]\n")) {
            correct++;
        } else {
            printf("\n  %%D %%T %%F printed:\n%s\n", out);
        }
        mem.free(out);
        cleanupPrintFmt();
    }

    /* %%Q is a literal, not a conversion */
    tests++;
    if (prints("void main() { printf(\"100%%Q %d\\n\", 1); }\n", "100%Q 1\n")) { correct++; }

    /* Print itself, with a format that isn't a literal */
    tests++;
    if (prints("void main() { u8 *f = \"%Q|%d\\n\"; Print(f, \"t\\tab\", 5); }\n",
                         "t\\tab|5\n")) { correct++; }

    /* the argument check counts ktm conversions */
    tests++;
    if (rejects("void main() { printf(\"%Q\\n\"); }\n",
                            "expects 1 argument but 0 were supplied")) { correct++; }
    tests++;
    if (rejects("void main() { printf(\"%Q %d\\n\", \"a\"); }\n",
                            "expects 2 arguments but 1 was supplied")) { correct++; }
    tests++;
    if (rejects("import date;\n\nvoid main() { printf(\"%D %d\\n\", date.now, 1, 2); }\n",
                            "expects 2 arguments but 3 were supplied")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
