import cstr, fs, libc, proc, str, test;

/* With -run or -jit, everything after the source file is the program's,
 * even what looks like a ktm option (-v, -o x, other.ktm); `--` ends
 * ktm's options and isn't passed on; a build still takes -o after the
 * file. (Before v0.17.1 ktm took -v itself and passed `--` through.) */

#define PROG "__668_prog.ktm"
#define OUT  "__668_prog.out"
#define BIN  "./__668_prog.bin"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

void cleanup() {
    fs.remove(PROG);
    fs.remove(OUT);
    fs.remove(BIN);
}

/* `ktm <before> PROG <after>`: what the program printed is want */
bool argsAre(u8 *before, u8 *after, u8 *want) {
    i64 rc = proc.shellf("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} %s %s %s > %s 2>&1",
                         before, PROG, after, OUT);
    String got = fs.readAll(OUT);
    bool ok = rc == 0 && got.ptr != null && str.equals(got, cstr.view(want));
    if (!ok) { printf("\n  ktm %s %s %s: rc %d [%.*s]", before, PROG, after, rc, got.len, got.ptr); }
    got.free();
    return ok;
}

u8 *gProg =
"i32 main(i32 argc, u8 **argv)
{
  for (i32 i = 1; i < argc; i++) { printf(\"[%s]\", argv[i]); }
  printf(\"\\n\");
  return 0;
}
";

i32 main()
{
    printf("Test - program arguments after the source file: ");
    cleanup();
    check(fs.writeAll(PROG, cstr.view(gProg), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC),
          "write the program");

    check(argsAre("-run", "-v --name=x other.ktm", "[-v][--name=x][other.ktm]\n"),
          "-run: -v and a .ktm name are the program's");
    check(argsAre("-jit", "-v -o x", "[-v][-o][x]\n"), "-jit: -v and -o are the program's");
    check(argsAre("-run", "-- -v", "[-v]\n"), "`--` is not passed on");
    check(argsAre("", "-run -v", "[-v]\n"), "-run after the file");
    check(argsAre("-run", "", "\n"), "no arguments");

    i64 rc = proc.shellf("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} %s -o %s > %s 2>&1",
                         PROG, BIN, OUT);
    check(rc == 0 && fs.exists(BIN), "a build still takes -o after the file");
    String out = proc.shellOutput("%s a -v", BIN);
    check(str.equals(out, "[a][-v]"), "the built program gets its arguments");
    out.free();

    cleanup();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
