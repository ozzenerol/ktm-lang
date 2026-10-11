import cstr, fs, libc, mem, proc, test;

/* `ktm -run file.ktm` used to free its command string before handing it
 * to system(), so it ran nothing and exited 0. It must compile the
 * program, run it with any trailing arguments, exit with the program's
 * exit code and leave no binary behind (nor clobber a user's ./a.out).
 * ktm is run from a scratch directory: the test runner itself is
 * ./a.out in src/tests. */

#define RUN_TEST_DIR  "ktm-73-run-dir"
#define RUN_TEST_MAIN "ktm-73-run-main.ktm"
#define RUN_TEST_TOP  "ktm-73-run-top.ktm"
#define RUN_TEST_OUT  "ktm-73-run.out"
/* A single literal: adjacent strings don't concatenate inside #define */
#define RUN_KTM "H=$PWD/../../ktm && cd ktm-73-run-dir && $H ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -run "

void cleanupRunTest(void) {
    proc.shell("rm -rf " RUN_TEST_DIR " " RUN_TEST_MAIN " " RUN_TEST_TOP " " RUN_TEST_OUT);
}

bool writeSource(u8 *path, u8 *source) {
    return fs.writeAll(cstr.view(path), cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
}

/* The captured output of the last run must be exactly `expected` */
bool outputIs(u8 *expected) {
    u8 *out = fs.readAll(RUN_TEST_OUT).take();
    bool ok;
    if (!out) { return false; }
    ok = cstr.cmp(out, expected) == 0;
    if (!ok) { printf("got: '%s'\n", out); }
    mem.free(out);
    return ok;
}

i32 main() {
    printf("Test - ktm -run: ");
    i64 tests = 6;
    i64 correct = 0;

    cleanupRunTest();
    proc.shell("mkdir -p " RUN_TEST_DIR);

    writeSource(RUN_TEST_MAIN,
"import os;

void main(i32 argc, u8 **argv)
{
  i64 i;
  printf(\"run works argc=%d\\n\", argc);
  for (i = 1; i < argc; i++) { printf(\"[%s]\\n\", argv[i]); }
  if (argc > 1) { os.exit(7); }
}
");
    writeSource(RUN_TEST_TOP,
"i64 x = 40;
void main() { printf(\"top level %d\\n\", x + 2); }
");

    /* Program output reaches stdout and the exit code is the program's */
    if (proc.shell("(" RUN_KTM "../" RUN_TEST_MAIN ") > " RUN_TEST_OUT " 2>&1") == 0) { correct++; }
    else { printf("Failed - -run exit code 0\n"); }

    if (outputIs("run works argc=1\n")) { correct++; }
    else { printf("Failed - -run output\n"); }

    /* No binary is left in the working directory */
    if (proc.shell("test ! -e " RUN_TEST_DIR "/a.out") == 0) { correct++; }
    else { printf("Failed - -run left ./a.out behind\n"); }

    /* Trailing arguments are passed through, Exit(7) becomes ktm's exit
     * code and an existing ./a.out is left alone */
    proc.shell("echo keep > " RUN_TEST_DIR "/a.out");
    if (proc.shell("(" RUN_KTM "../" RUN_TEST_MAIN " foo 'b c') > " RUN_TEST_OUT " 2>&1;"
                         " test $? -eq 7 && grep -qx keep " RUN_TEST_DIR "/a.out") == 0) { correct++; }
    else { printf("Failed - -run exit code 7 / ./a.out kept\n"); }

    if (outputIs("run works argc=3\n[foo]\n[b c]\n")) { correct++; }
    else { printf("Failed - -run arguments\n"); }

    /* A program with a global and main */
    if (proc.shell("(" RUN_KTM "../" RUN_TEST_TOP ") > " RUN_TEST_OUT " 2>&1") == 0 &&
            outputIs("top level 42\n")) { correct++; }
    else { printf("Failed - -run top-level program\n"); }

    cleanupRunTest();
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
