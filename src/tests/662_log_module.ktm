import cstr, fs, libc, log, str, test;

/* log (v0.15.0): one minimal line per call (UTC time, the level's full
 * name, file:line and the function when asked, the message on one line),
 * a level filter that can change at any time, size rotation keeping
 * `keep` files, and a threadSafe Logger shared by several threads. */

#define LOG_FILE "__662_log.log"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

void cleanup() {
    fs.remove(LOG_FILE);
    for (i64 i = 1; i <= 60; i++) {
        String name = str.format("%s.%d", LOG_FILE, i);
        fs.remove(name);
        name.free();
    }
}

String readFile(u8 *path) {
    return fs.readAll(cstr.view(path));
}

String rotated(i64 n) {
    return str.format("%s.%d", LOG_FILE, n);
}

i64 countLines(u8[] text) {
    i64 n = 0;
    for (i64 i = 0; i < text.len; i++) { if (text[i] == '\n') { n++; } }
    return n;
}

/* 2026-10-13T14:02:11.482Z: digits where they go, then a space */
bool timeShaped(u8[] l) {
    u8 *shape = "dddd-dd-ddTdd:dd:dd.dddZ ";
    if (l.len < 25) { return false; }
    for (i64 i = 0; i < 25; i++) {
        if (shape[i] == 'd') { if (l[i] < '0' || l[i] > '9') { return false; } }
        else if (l[i] != shape[i]) { return false; }
    }
    return true;
}

/* Every line of text is time-shaped and ends in a '\n' */
bool allShaped(u8[] text) {
    i64 start = 0;
    for (i64 i = 0; i < text.len; i++) {
        if (text[i] == '\n') {
            if (!timeShaped(text[start:i + 1])) { return false; }
            start = i + 1;
        }
    }
    return start == text.len;
}

/* Line n (0-based) of text, after its time */
u8[] lineAfterTime(u8[] text, i64 n) {
    i64 start = 0;
    for (i64 i = 0; i < text.len; i++) {
        if (text[i] == '\n') {
            if (n == 0) { return text[start + 25:i]; }
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
    if (!ok) { printf("\n  line %d: got [%.*s]\n          want [%s]", n, got.len, got.ptr, want); }
    return ok;
}

i64 lineHere(@caller("line") i64 line) { return line; }

void helper(log.Logger *lg) {
    lg.warn("from %s", "helper");
}

log.Logger *gShared;

void *worker(void *arg) {
    i64 id = (i64)arg;
    for (i64 i = 0; i < 400; i++) { gShared.info("worker %d line %d", id, i); }
    return null;
}

i32 main()
{
    printf("Test - log module: ");
    cleanup();

    /* the defaults */
    log.Options o = log.options();
    check(o.path == null && !o.threadSafe && o.maxBytes == 0 && o.keep == 0 &&
          !o.showFile && !o.showFunc && o.level == log.Level.info, "options() defaults");

    /* the line format, without and with file and function */
    o.path = LOG_FILE;
    log.Logger *lg = log.open(&o);
    lg.info("plain %d", 1);
    lg.debug("below the level");
    lg.warn("two\nlines\r");
    lg.error("an error");
    lg.close();
    o.showFile = true;
    lg = log.open(&o);
    i64 at = 0;
    lg.info("with file"); at = lineHere();
    lg.close();
    o.showFunc = true;
    lg = log.open(&o);
    helper(lg);
    lg.close();
    o.showFile = false;
    lg = log.open(&o);
    lg.info("func only");
    lg.close();

    String text = readFile(LOG_FILE);
    check(countLines(text) == 6, "debug is below INFO; the others are appended");
    check(allShaped(text), "every line starts with the UTC time");
    check(lineIs(text, 0, "INFO plain 1"), "a plain line");
    check(lineIs(text, 1, "WARN two\\nlines\\r"), "newlines written as \\n");
    check(lineIs(text, 2, "ERROR an error"), "ERROR");
    u8 want[128];
    libc.snprintf(want, 128, "INFO 662_log_module.ktm:%d with file", at);
    check(lineIs(text, 3, want), "file:line");
    check(lineIs(text, 4, "WARN 662_log_module.ktm:98 helper: from helper"), "file:line and the function");
    check(lineIs(text, 5, "INFO main: func only"), "the function only");
    text.free();
    cleanup();

    /* the level, changed while open */
    o = log.options();
    o.path = LOG_FILE;
    o.level = log.Level.debug;
    lg = log.open(&o);
    lg.debug("d1");
    lg.level = log.Level.error;
    lg.info("dropped");
    lg.warn("dropped");
    lg.error("e1");
    lg.level = log.Level.warn;
    lg.warn("w1");
    check(lg.failed == 0, "no failed writes");
    lg.close();
    text = readFile(LOG_FILE);
    check(countLines(text) == 3 && lineIs(text, 0, "DEBUG d1") && lineIs(text, 1, "ERROR e1") &&
          lineIs(text, 2, "WARN w1"), "lg.level filters from then on");
    text.free();
    cleanup();

    /* rotation: each line is 25 + 18 bytes, 4 fit in 200 */
    o = log.options();
    o.path = LOG_FILE;
    o.maxBytes = 200;
    o.keep = 2;
    lg = log.open(&o);
    for (i64 i = 0; i < 20; i++) { lg.info("line number %04d", i); }
    lg.close();
    text = readFile(LOG_FILE);
    String one = rotated(1), two = rotated(2), three = rotated(3);
    String t1 = readFile(one.ptr), t2 = readFile(two.ptr);
    check(text.len <= 200 && t1.len <= 200 && t2.len <= 200, "no file past maxBytes");
    check(countLines(text) == 4 && countLines(t1) == 4 && countLines(t2) == 4, "4 lines a file");
    check(lineIs(text, 3, "INFO line number 0019") && lineIs(t1, 3, "INFO line number 0015") &&
          lineIs(t2, 0, "INFO line number 0008"), "the newest in path, older in .1 and .2");
    check(!fs.exists(three), "keep 2: no .3");
    text.free(); t1.free(); t2.free();

    /* reopening counts what the file has */
    lg = log.open(&o);
    lg.info("line number %04d", 20);
    lg.close();
    text = readFile(LOG_FILE);
    t1 = readFile(one.ptr);
    check(countLines(text) == 1 && lineIs(t1, 3, "INFO line number 0019"),
          "a reopened file rotates by its size");
    text.free(); t1.free();
    cleanup();

    /* keep 0: the file is emptied */
    o.keep = 0;
    lg = log.open(&o);
    for (i64 i = 0; i < 6; i++) { lg.info("line number %04d", i); }
    lg.close();
    text = readFile(LOG_FILE);
    check(countLines(text) == 2 && lineIs(text, 0, "INFO line number 0004") && !fs.exists(one),
          "keep 0 empties the file");
    text.free();
    cleanup();

    /* threadSafe: 4 threads, 1600 whole lines across rotated files */
    o = log.options();
    o.path = LOG_FILE;
    o.threadSafe = true;
    o.maxBytes = 8000;
    o.keep = 30;
    gShared = log.open(&o);
    libc.pthread_t *th[4];
    for (i64 i = 0; i < 4; i++) { libc.pthread_create(&th[i], null, &worker, (void *)i); }
    for (i64 i = 0; i < 4; i++) { libc.pthread_join(th[i]); }
    check(gShared.failed == 0, "threads: no failed writes");
    gShared.close();
    i64 lines = 0;
    bool shaped = true;
    for (i64 i = 0; i <= 30; i++) {
        String name = i ? rotated(i) : str.from(LOG_FILE);
        if (fs.exists(name)) {
            String part = readFile(name.ptr);
            lines += countLines(part);
            if (!allShaped(part) || part.len > 8000) { shaped = false; }
            part.free();
        }
        name.free();
    }
    check(lines == 1600, "threads: every line is there");
    check(shaped, "threads: lines never mix or cross a rotation");
    cleanup();

    /* a file that can't be opened */
    o = log.options();
    o.path = "__662_no_such_dir/x.log";
    bool threw = false;
    try { lg = log.open(&o); } catch (Error e) { threw = e.code == log.ERR_LOG; }
    check(threw, "log.open throws ERR_LOG");

    one.free(); two.free(); three.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}

