import date, str, test;

/* date clocks and HTTP dates (v0.5.0): monoMs/monoNs only go forward,
 * sleepMs sleeps at least that long, formatUnix is strftime of a Unix time,
 * httpDate/parseHttpDate write and read RFC 9110 dates (all three forms). */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

bool httpIs(i64 t, u8[] want) {
    String s = date.httpDate(t);
    defer s.free();
    bool ok = s.equals(want);
    if (!ok) { printf("\n  httpDate(%d) = %s\n", t, s); }
    return ok;
}

bool parsesTo(u8[] text, i64 want) {
    i64 t = -12345;
    bool ok = date.parseHttpDate(text, &t) && t == want;
    if (!ok) { printf("\n  parseHttpDate(%s) = %d\n", text, t); }
    return ok;
}

bool rejects(u8[] text) {
    i64 t = 777;
    return !date.parseHttpDate(text, &t) && t == 777;
}

i32 main() {
    printf("Test - date clocks and HTTP dates: ");

    i64 a = date.monoMs(), na = date.monoNs();
    date.sleepMs(30);
    i64 b = date.monoMs(), nb = date.monoNs();
    check(b - a >= 30 && b - a < 5000, "sleepMs(30) takes at least 30 ms");
    check(nb - na >= 30000000, "monoNs moves with monoMs");
    check(a > 0, "monoMs is positive");
    date.sleepMs(0);
    date.sleepMs(-5);
    check(date.monoMs() - b < 1000, "sleepMs of 0 or less returns at once");

    i64 now = date.unixNow();
    check(now > 1700000000 && now - date.nowMs() / 1000 <= 1, "unixNow agrees with nowMs");

    /* RFC 9110's example: 784111777 is Sun, 06 Nov 1994 08:49:37 GMT */
    check(httpIs(784111777, "Sun, 06 Nov 1994 08:49:37 GMT"), "httpDate of RFC 9110's example");
    check(httpIs(0, "Thu, 01 Jan 1970 00:00:00 GMT"), "httpDate of 0");
    check(httpIs(951782400, "Tue, 29 Feb 2000 00:00:00 GMT"), "httpDate of a leap day");
    check(httpIs(-1, "Wed, 31 Dec 1969 23:59:59 GMT"), "httpDate before 1970");
    check(httpIs(4102444799, "Thu, 31 Dec 2099 23:59:59 GMT"), "httpDate in 2099");

    check(parsesTo("Sun, 06 Nov 1994 08:49:37 GMT", 784111777), "parse IMF-fixdate");
    check(parsesTo("Sunday, 06-Nov-94 08:49:37 GMT", 784111777), "parse RFC 850");
    check(parsesTo("Sun Nov  6 08:49:37 1994", 784111777), "parse asctime");
    check(parsesTo("Thu Feb 29 12:00:00 2024", 1709208000), "parse asctime, 2 digits");
    check(parsesTo("  Tue, 29 Feb 2000 00:00:00 GMT \r\n", 951782400), "spaces around are fine");
    check(parsesTo("Friday, 01-Jan-21 00:00:00 GMT", 1609459200), "RFC 850 year 21 is 2021");
    check(rejects(""), "empty");
    check(rejects("Sun, 06 Nov 1994 08:49:37"), "no GMT");
    check(rejects("Sun, 06 Nov 1994 08:49:37 GMT x"), "trailing text");
    check(rejects("Sun, 31 Feb 1994 08:49:37 GMT"), "31 Feb");
    check(rejects("Sun, 06 Now 1994 08:49:37 GMT"), "bad month");
    check(rejects("Sun, 06 Nov 1994 24:00:00 GMT"), "hour 24");
    check(rejects("yesterday"), "not a date");

    /* a round trip over a range of times */
    bool round = true;
    for (i64 t = -86400 * 400; round && t < 4102444800; t += 86400 * 37 + 3601) {
        String s = date.httpDate(t);
        i64 back;
        round = date.parseHttpDate(s, &back) && back == t;
        if (!round) { printf("\n  round trip of %d: %s\n", t, s); }
        s.free();
    }
    check(round, "httpDate -> parseHttpDate round trip");

    String f = date.formatUnix(784111777, "%Y-%m-%d %H:%M:%S");
    check(f.equals("1994-11-06 08:49:37"), "formatUnix in UTC");
    f.free();
    f = date.formatUnix(784111777, "%j");
    check(f.equals("310"), "formatUnix %j");
    f.free();
    f = date.formatUnix(0, "");
    check(f.len == 0, "formatUnix of an empty format");
    f.free();
    String big = str.new();
    for (i64 i = 0; i < 100; i++) { big.append("%Y "); }
    f = date.formatUnix(0, big);
    check(f.len == 500, "formatUnix longer than its first try");
    f.free();
    big.free();
    f = date.formatUnix(now, "%H", false);
    check(f.len == 2, "formatUnix in local time");
    f.free();

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
