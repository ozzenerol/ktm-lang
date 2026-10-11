import cstr, fs, libc, mem, os, proc, test;

/* std.ktmh (and the library's defs.ktmh) defined STDOUT as 0 and STDIN as
 * 2, so Write(STDOUT, ...) wrote to stdin: it only worked while fd 0
 * happened to be a read/write terminal and was lost when stdout was
 * redirected. They are the POSIX numbers: STDIN 0, STDOUT 1, STDERR 2.
 * The program below is run through the JIT with each stream
 * redirected to a file, which is read back. */

#define FD_TEST_PROG "./ktm-84-prog.ktm"
#define FD_TEST_OUT  "./ktm-84-out.txt"
#define FD_TEST_ERR  "./ktm-84-err.txt"
/* A single literal: adjacent strings don't concatenate inside #define */
#define FD_TEST_KTM "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit ./ktm-84-prog.ktm"

void cleanupFdTest(void) {
    proc.shell("rm -f " FD_TEST_PROG " " FD_TEST_OUT " " FD_TEST_ERR);
}

/* The file at `path` holds exactly `expected` */
bool fileIs(u8 *path, u8 *expected) {
    u8 *got = fs.readAll(cstr.view(path)).take();
    bool ok;
    if (!got) {
        printf("%s missing\n", path);
        return false;
    }
    ok = cstr.cmp(got, expected) == 0;
    if (!ok) { printf("%s: got '%s', want '%s'\n", path, got, expected); }
    mem.free(got);
    return ok;
}

i32 main() {
    printf("Test - STDIN, STDOUT and STDERR: ");
    i64 tests = 7, correct = 0;
    u8 *prog;

    if (os.STDIN == 0 && os.STDOUT == 1 && os.STDERR == 2) { correct++; }
    else { printf("Failed - STDIN %d, STDOUT %d, STDERR %d\n", os.STDIN, os.STDOUT, os.STDERR); }

    cleanupFdTest();
    /* Copies stdin to stdout, then writes one line to each stream */
    prog =
"import libc, os;

i32 main()
{
  u8 buf[64];
  i64 n = libc.read(os.STDIN, buf, 64);
  if (n > 0) { libc.write(os.STDOUT, buf, n); }
  libc.write(os.STDOUT, \"to stdout\\n\", 10);
  libc.write(os.STDERR, \"to stderr\\n\", 10);
  return 0;
}
";
    fs.writeAll(FD_TEST_PROG, cstr.view(prog), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);

    /* stdin is /dev/null (read-only), stdout and stderr are files */
    if (proc.shell(FD_TEST_KTM " < /dev/null > " FD_TEST_OUT " 2> " FD_TEST_ERR) == 0) { correct++; }
    else { printf("Failed - running the program\n"); }
    if (fileIs(FD_TEST_OUT, "to stdout\n")) { correct++; }
    else { printf("Failed - Write(STDOUT, ...) to a redirected stdout\n"); }
    if (fileIs(FD_TEST_ERR, "to stderr\n")) { correct++; }
    else { printf("Failed - Write(STDERR, ...) to a redirected stderr\n"); }

    /* STDIN reads piped input */
    if (proc.shell("printf 'piped\\n' | " FD_TEST_KTM " > " FD_TEST_OUT " 2> " FD_TEST_ERR) == 0) { correct++; }
    else { printf("Failed - running the program with piped stdin\n"); }
    if (fileIs(FD_TEST_OUT, "piped\nto stdout\n")) { correct++; }
    else { printf("Failed - read(STDIN, ...) from a pipe\n"); }

    /* stdout and stderr stay separate when both go to one pipe */
    if (proc.shell(FD_TEST_KTM " < /dev/null 2>/dev/null | grep -qx 'to stdout'") == 0
            && proc.shell(FD_TEST_KTM " < /dev/null 2>&1 >/dev/null | grep -qx 'to stderr'") == 0) { correct++; }
    else { printf("Failed - stdout and stderr through pipes\n"); }

    cleanupFdTest();
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
