import fs, libc, log, str, test;

/* Log lines put every message in one column (v0.18.0): the level padded
 * to 5 (INFO, WARN, DEBUG, ERROR), the file:line and function part padded
 * to the widest so far (at least 20). Without showFile/showFunc only the
 * level is padded. */

#define LOG_FILE "__679.log"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s", what); }
}

/* Where "msg" starts on each line; -1 if a line has none */
i64 msgColumn(u8[] text, i64 *lines) {
    i64 col = -2, start = 0;
    *lines = 0;
    for (i64 i = 0; i < text.len; i++) {
        if (text[i] != '\n') { continue; }
        u8[] l = text[start:i];
        i64 at = str.find(l, "msg");
        if (at < 0 || (col != -2 && at != col)) {
            printf("\n  line [%.*s]", l.len, l.ptr);
            return -1;
        }
        col = at;
        (*lines)++;
        start = i + 1;
    }
    return col;
}

void deeper() { log.error("msg four"); }

i32 main()
{
    printf("Test - log messages in one column: ");
    fs.remove(LOG_FILE);

    log.Options o = log.options();
    o.path = LOG_FILE;
    o.level = log.Level.debug;
    o.showFile = true;
    o.showFunc = true;
    log.setup(&o);
    deeper();
    log.info("msg one");
    log.debug("msg two");
    log.warn("msg three");
    log.shutdown();

    String text = fs.readAll(LOG_FILE);
    i64 n = 0;
    i64 col = msgColumn(text[0:text.len], &n);
    check(n == 4 && col > 0, "four lines, the messages in one column");
    text.free();
    fs.remove(LOG_FILE);

    o.showFile = false;
    o.showFunc = false;
    log.setup(&o);
    log.info("msg one");
    log.error("msg two");
    log.shutdown();
    text = fs.readAll(LOG_FILE);
    col = msgColumn(text[0:text.len], &n);
    check(n == 2 && col == 31, "no file or function: after the padded level");
    text.free();
    fs.remove(LOG_FILE);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
