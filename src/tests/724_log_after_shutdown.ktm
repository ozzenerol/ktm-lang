import cstr, fs, libc, proc, str, test;

/* Logging after log.shutdown() (v0.18.0): the lines go to stderr (or
 * stdout with toStdout) at the closed logger's level, as it wrote them
 * without its file, and the first one is reported once with where it
 * came from. Before, they went to stderr at INFO, so the DEBUG lines of
 * a program whose `defer log.shutdown();` ran early in a try block
 * vanished. A log.setup after a shutdown reports nothing. */

#define PROG "__724_prog.ktm"
#define OUT "__724.out"
#define ERR "__724.err"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s", what); }
}

u8 *gEarly =
"import log;

void main()
{
  try {
    log.Options o = log.options();
    o.path = \"__724.log\";
    o.level = log.Level.debug;
    o.toStdout = true;
    log.setup(&o);
    defer log.shutdown();
  } catch (Error e) {}
  log.debug(\"first\");
  log.debug(\"second\");
}
";

u8 *gAgain =
"import log;

void main()
{
  log.Options o = log.options();
  o.path = \"__724.log\";
  log.setup(&o);
  log.shutdown();
  log.setup(&o);
  log.info(\"in the file\");
  log.shutdown();
}
";

void run(u8 *src, String *out, String *err) {
    fs.writeAll(PROG, cstr.view(src));
    proc.shellf("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit %s > %s 2> %s", PROG, OUT, ERR);
    *out = fs.readAll(OUT);
    *err = fs.readAll(ERR);
}

i32 main() {
    printf("Test - log after shutdown: ");
    String out, err;

    run(gEarly, &out, &err);
    check(out.ptr && libc.strstr(out.ptr, "DEBUG first") && libc.strstr(out.ptr, "DEBUG second"),
          "the DEBUG lines still come out, on stdout as set up");
    check(err.ptr && libc.strstr(err.ptr, "log: a line logged after log.shutdown() at __724_prog.ktm:13 goes to stdout"),
          "reported once with where it came from");
    check(err.ptr && str.find(err[0:err.len], "log:") == str.findLast(err[0:err.len], "log:"), "only once");
    out.free();
    err.free();

    run(gAgain, &out, &err);
    check(err.len == 0 && out.len == 0, "setup again after a shutdown: nothing reported");
    String file = fs.readAll("__724.log");
    check(file.ptr && libc.strstr(file.ptr, "INFO  in the file"), "and the line is in the file");
    file.free();
    out.free();
    err.free();

    fs.remove(PROG);
    fs.remove(OUT);
    fs.remove(ERR);
    fs.remove("__724.log");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
