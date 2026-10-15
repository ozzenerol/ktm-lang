import cstr, fs, log, str, test;

/* log's default logger (v0.16.0): log.info(...) and the others from
 * anywhere, stderr at INFO until log.setup, file:line and the function
 * from @caller, log.setLevel, a failed setup keeping the old logger, and
 * log.shutdown going back to stderr. */

#define LOG_FILE "__665_log.log"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* Line n (0-based) of text, after its 25-byte time */
u8[] lineAfterTime(u8[] text, i64 n) {
    i64 start = 0;
    for (i64 i = 0; i < text.len; i++) {
        if (text[i] == '\n') {
            if (n == 0) { return i - start > 25 ? text[start + 25:i] : text[0:0]; }
            n--;
            start = i + 1;
        }
    }
    return text[0:0];
}

/* the line with each run of spaces as one: the level and file:line
 * columns are padded to line the messages up (v0.18.0) */
String squeeze(u8[] l) {
    String s = str.new();
    for (i64 i = 0; i < l.len; i++) {
        if (l[i] == ' ' && i > 0 && l[i - 1] == ' ') { continue; }
        s.appendByte(l[i]);
    }
    return s;
}

bool lineIs(u8[] text, i64 n, u8 *want) {
    u8[] got = lineAfterTime(text, n);
    scoped String flat = squeeze(got);
    bool ok = str.equals(flat, cstr.view(want));
    if (!ok) { printf("\n  line %d: got [%.*s]\n          want [%s]", n, got.len, got.ptr, want); }
    return ok;
}

void helper() {
    log.debug("debug %d", 1);
    log.info("info %s", "two");
}

i32 main()
{
    printf("Test - log default logger: ");
    fs.remove(LOG_FILE);

    /* never set up: stderr at INFO */
    log.Logger *d = log.logger();
    check(d != null && d.fd == 2 && d.level == log.Level.info, "the default is stderr at INFO");

    log.Options o = log.options();
    o.path = LOG_FILE;
    o.showFile = true;
    o.showFunc = true;
    log.setup(&o);
    check(log.logger().fd > 2, "setup opens the file");
    log.info("from main");
    helper();
    log.setLevel(log.Level.debug);
    helper();
    log.setLevel(log.Level.error);
    log.warn("not written");
    log.error("err\nline");
    log.logger().level = log.Level.info;

    /* a setup that fails keeps the logger it had */
    log.Options bad = log.options();
    bad.path = "__665_no_such_dir/x.log";
    bool threw = false;
    try { log.setup(&bad); } catch (Error e) { threw = e.code == log.ERR_LOG; }
    check(threw, "a failed setup throws ERR_LOG");
    log.warn("still here");

    log.shutdown();
    check(log.logger().fd == 2 && log.logger().level == log.Level.info,
          "shutdown goes back to stderr at INFO");

    String text = fs.readAll(LOG_FILE);
    check(lineIs(text, 0, "INFO 665_log_default.ktm:69 main: from main"), "a call from main");
    check(lineIs(text, 1, "INFO 665_log_default.ktm:51 helper: info two"), "below the level is skipped");
    check(lineIs(text, 2, "DEBUG 665_log_default.ktm:50 helper: debug 1"), "setLevel debug");
    check(lineIs(text, 3, "INFO 665_log_default.ktm:51 helper: info two"), "the call's own line");
    check(lineIs(text, 4, "ERROR 665_log_default.ktm:75 main: err\\nline"), "setLevel error");
    check(lineIs(text, 5, "WARN 665_log_default.ktm:84 main: still here"), "the old logger kept");
    check(lineAfterTime(text, 6).len == 0, "nothing more");
    text.free();
    fs.remove(LOG_FILE);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
