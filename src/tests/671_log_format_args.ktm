import cstr, fs, libc, log, proc, str, test;

/* log's calls take a String or a u8[] for %s, as printf does (they were
 * "a String can't be passed as an untyped extra argument"), for the
 * default logger and a Logger, and their format is checked against the
 * arguments. */

#define LOG_FILE "__671.log"
#define PROG     "__671_prog.ktm"
#define OUT      "__671_prog.out"

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
    ad String flat = squeeze(got);
    bool ok = str.equals(flat, cstr.view(want));
    if (!ok) { printf("\n  line %d: got [%.*s] want [%s]", n, got.len, got.ptr, want); }
    return ok;
}

bool compileFails(u8 *src, u8 *want) {
    fs.writeAll(PROG, cstr.view(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    i64 rc = proc.shellf("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -S -o- %s > %s 2>&1", PROG, OUT);
    String out = fs.readAll(OUT);
    bool ok = rc != 0 && out.ptr != null && libc.strstr(out.ptr, want) != null;
    if (!ok) { printf("\n  got [%.*s]", out.len, out.ptr); }
    out.free();
    fs.remove(PROG);
    fs.remove(OUT);
    return ok;
}

i32 main()
{
    printf("Test - log format arguments: ");
    fs.remove(LOG_FILE);

    ad String name = str.format("users_%d", 2);
    u8[] view = "orders and more";
    view = view[0:6];

    log.Options o = log.options();
    o.path = LOG_FILE;
    log.setup(&o);
    log.info("table %s", name);
    log.warn("view %s, %d rows", view, 7);
    log.debug("not written %s", name);
    log.error("%s|%s", view, name);
    log.Logger *lg = log.logger();
    lg.info("method %s", name);
    lg.error("method %s %d", view, 3);
    log.shutdown();

    String text = fs.readAll(LOG_FILE);
    check(lineIs(text, 0, "INFO table users_2"), "a String for %s");
    check(lineIs(text, 1, "WARN view orders, 7 rows"), "a u8[] view, only its own bytes");
    check(lineIs(text, 2, "ERROR orders|users_2"), "two of them");
    check(lineIs(text, 3, "INFO method users_2"), "a Logger's method, a String");
    check(lineIs(text, 4, "ERROR method orders 3"), "a Logger's method, a u8[]");
    check(lineAfterTime(text, 5).len == 0, "nothing more");
    text.free();
    fs.remove(LOG_FILE);

    check(compileFails("import log;\nvoid main() { log.warn(\"%d %s\", 1); }\n",
                       "format expects 2 arguments but 1 was supplied"),
          "log.warn's format is checked");
    check(compileFails("import log;\nvoid main() { log.Options o = log.options(); log.Logger *l = log.open(&o); l.info(\"%s\", \"x\", 2); }\n",
                       "format expects 1 argument but 2 were supplied"),
          "a Logger's format is checked");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
