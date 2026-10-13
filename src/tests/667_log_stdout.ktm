import cstr, fs, libc, proc, str, test;

/* log.Options.toStdout (v0.17.0): with no path a Logger, or the default
 * logger, writes to stdout, in order with printf; stderr stays empty;
 * close leaves stdout open. */

#define PROG "__667_prog.ktm"
#define OUT  "__667_prog.out"
#define ERR  "__667_prog.err"

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

/* stdout without the 25-byte times at the start of the log lines */
String stripTimes(u8[] text) {
    String s = str.new();
    i64 start = 0;
    for (i64 i = 0; i < text.len; i++) {
        if (text[i] == '\n') {
            u8[] l = text[start:i + 1];
            if (l.len > 25 && l[4] == '-' && l[24] == ' ') { l = l[25:l.len]; }
            s.append(l);
            start = i + 1;
        }
    }
    return s;
}

u8 *gProg =
"import log;

void main()
{
  log.Options o = log.options();
  o.toStdout = true;
  log.Logger *lg = log.open(&o);
  printf(\"one\\n\");
  lg.info(\"two\");
  printf(\"three\\n\");
  lg.close();
  printf(\"four\\n\");
  log.setup(&o);
  log.warn(\"five\");
  printf(\"six\\n\");
  log.shutdown();
}
";

i32 main()
{
    printf("Test - log to stdout: ");
    cleanup();
    check(fs.writeAll(PROG, cstr.view(gProg), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC),
          "write the program");
    u8 *modes[2] = { "-run", "-jit" };
    for (i64 m = 0; m < 2; m++) {
        i64 rc = proc.shellf("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} %s %s > %s 2> %s",
                             modes[m], PROG, OUT, ERR);
        check(rc == 0, "the program runs");
        String out = fs.readAll(OUT);
        String plain = stripTimes(out);
        bool ok = str.equals(plain, "one\nINFO two\nthree\nfour\nWARN five\nsix\n");
        if (!ok) { printf("\n  stdout: [%.*s]", out.len, out.ptr); }
        check(ok, "log lines on stdout in order with printf");
        String err = fs.readAll(ERR);
        check(err.len == 0, "nothing on stderr");
        out.free(); plain.free(); err.free();
    }
    cleanup();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
