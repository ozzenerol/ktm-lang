import cstr, fs, libc, proc, str, test;

/* libc.EXIT_SUCCESS / EXIT_FAILURE, libc.stdout / stderr and
 * libc.fprintf (v0.17.0): fprintf to stdout stays in order with printf,
 * the exit status is the one asked for, and fprintf's format is checked
 * like printf's (libc.printf and libc.snprintf now too). */

#define PROG "__666_prog.ktm"
#define OUT  "__666_prog.out"
#define ERR  "__666_prog.err"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

void cleanup() {
    fs.remove(PROG);
    fs.remove(OUT);
    fs.remove(ERR);
}

bool writeProg(u8 *source) {
    return fs.writeAll(PROG, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
}

/* `ktm <mode> PROG <arg>`: its exit status, stdout and stderr in the files */
i64 runProg(u8 *mode, u8 *arg) {
    return proc.shellf("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} %s %s %s > %s 2> %s",
                       mode, PROG, arg, OUT, ERR);
}

bool fileIs(u8 *path, u8 *want) {
    String got = fs.readAll(cstr.view(path));
    bool ok = got.ptr != null && str.equals(got, cstr.view(want));
    if (!ok) { printf("\n  %s: got [%.*s] want [%s]", path, got.len, got.ptr, want); }
    got.free();
    return ok;
}

bool fileHas(u8 *path, u8 *want) {
    String got = fs.readAll(cstr.view(path));
    bool ok = got.ptr != null && libc.strstr(got.ptr, want) != null;
    if (!ok) { printf("\n  %s: [%.*s] lacks [%s]", path, got.len, got.ptr, want); }
    got.free();
    return ok;
}

u8 *gProg =
"import libc;

i32 main(i32 argc, u8 **argv)
{
  printf(\"out1\\n\");
  libc.fprintf(libc.stdout, \"out2 %d\\n\", 2);
  libc.fprintf(libc.stderr, \"err %s %d %.2f\\n\", \"x\", 42, 1.5);
  printf(\"out3\\n\");
  if (argc > 1) { libc.exit(libc.EXIT_FAILURE); }
  libc.exit(libc.EXIT_SUCCESS);
  return 3;
}
";

i32 main()
{
    printf("Test - libc stdio and exit codes: ");
    cleanup();

    check(libc.EXIT_SUCCESS == 0 && libc.EXIT_FAILURE == 1, "EXIT_SUCCESS 0, EXIT_FAILURE 1");
    check(libc.stdout != null && libc.stderr != null && libc.stdin != null, "the streams are there");
    check(libc.fprintf(libc.stdout, "") == 0, "fprintf returns the bytes written");

    check(writeProg(gProg), "write the program");
    u8 *modes[2] = { "-run", "-jit" };
    for (i64 m = 0; m < 2; m++) {
        check(runProg(modes[m], "") == 0, "EXIT_SUCCESS is status 0");
        check(fileIs(OUT, "out1\nout2 2\nout3\n"), "fprintf to stdout in order with printf");
        check(fileIs(ERR, "err x 42 1.50\n"), "fprintf to stderr");
        check(runProg(modes[m], "fail") == 1, "EXIT_FAILURE is status 1");
    }

    /* the format is checked as printf's is */
    check(writeProg("import libc;\nvoid main() { libc.fprintf(libc.stderr, \"%d %s\\n\", 1); }\n"),
          "write the program");
    check(runProg("-S -o-", "") != 0 && fileHas(ERR, "format expects 2 arguments but 1 was supplied"),
          "libc.fprintf's format is checked");
    check(writeProg("import libc;\nvoid main() { u8 b[8]; libc.snprintf(b, 8, \"%d %d\", 1); }\n"),
          "write the program");
    check(runProg("-S -o-", "") != 0 && fileHas(ERR, "format expects 2 arguments but 1 was supplied"),
          "libc.snprintf's format is checked");

    cleanup();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
