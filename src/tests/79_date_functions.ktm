import cstr, date, mem, test;

/* date.toParts, date.formatDate and date.formatTime segfaulted. The optimiser
 * folded `iadd t, param, k` into a later store's addressing mode across
 * a call, so `_ds->day_of_week = date.dayOfWeek(date)` stored through rdi
 * after date.dayOfWeek had clobbered it. Once that was fixed the time fields
 * were wrong (math.divModU64 did not divide) and date.firstDayOfMonth / date.lastDayOfMonth
 * returned the wrong days. On the arm64 JIT, `date.toParts(&ds, Now())`
 * in this big Main frame lost the CDate: its 16-byte slot sat past the
 * ldur/stur range and the register-offset store/load was not emitted. */

struct Pair {
    i64 a, b;
};

i64 addOne(i64 x) { return x + 1; }
void setB(Pair *p, i64 y) { p.b = addOne(y); }

date.Date mkDate(i64 y, i64 mo, i64 d, i64 h, i64 mi, i64 s) {
    date.Parts ds;
    mem.set(&ds, 0, sizeof(date.Parts));
    ds.year = y;
    ds.mon = mo;
    ds.dayOfMon = d;
    ds.hour = h;
    ds.min = mi;
    ds.sec = s;
    return date.fromParts(&ds);
}

i64 mkDay(i64 y, i64 mo, i64 d) {
    date.Date c = mkDate(y, mo, d, 0, 0, 0);
    return c.date;
}

/* Round trip through date.toParts and check every field. */
bool check(i64 y, i64 mo, i64 d, i64 h, i64 mi, i64 s, i64 dow) {
    date.Parts ds;
    date.toParts(&ds, mkDate(y, mo, d, h, mi, s));
    return ds.year == y && ds.mon == mo && ds.dayOfMon == d &&
                 ds.hour == h && ds.min == mi && ds.sec == s &&
                 ds.dayOfWeek == dow;
}

bool sameHour(date.Parts *a, date.Parts *b) {
    return a.year == b.year && a.mon == b.mon &&
                 a.dayOfMon == b.dayOfMon &&
                 a.dayOfWeek == b.dayOfWeek && a.hour == b.hour;
}

bool strIs(u8 *got, u8 *want) {
    bool ok = !cstr.cmp(got, want);
    if (!ok) { printf("Failed - got '%s' want '%s'\n", got, want); }
    mem.free(got);
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 16;
    date.Parts ds, nowDs;
    date.Date c;
    i64 d;

    printf("Test - CDate functions (Date2Struct, MPrintDate, MPrintTime): ");

    /* The miscompile itself: field store through a param after a call. */
    Pair pr;
    pr.b = 0;
    setB(&pr, 41);
    if (pr.b == 42) { correct++; }

    /* Known dates round trip, with the right day of week (0 = Sunday). */
    if (check(2000, 1, 1, 0, 0, 0, 6)) { correct++; }
    if (check(2024, 2, 29, 12, 34, 56, 4)) { correct++; }
    if (check(1999, 12, 31, 23, 59, 59, 5)) { correct++; }
    if (check(1970, 1, 1, 0, 0, 1, 4)) { correct++; }
    if (check(2023, 2, 28, 7, 8, 9, 2)) { correct++; }

    /* formatting. */
    c = mkDate(2024, 2, 29, 12, 34, 56);
    if (strIs(date.formatDate(c), "02/29/2024")) { correct++; }
    if (strIs(date.formatTime(c), "12:34:56")) { correct++; }
    if (strIs(date.format(c, "%m/%d/%Y %H:%M:%S"), "02/29/2024 12:34:56")) { correct++; }

    /* Day helpers. */
    d = mkDay(2024, 2, 15);
    if (date.firstDayOfMonth(d) == mkDay(2024, 2, 1) &&
            date.lastDayOfMonth(d) == mkDay(2024, 2, 29)) { correct++; }
    d = mkDay(1999, 12, 15);
    if (date.firstDayOfMonth(d) == mkDay(1999, 12, 1) &&
            date.lastDayOfMonth(d) == mkDay(1999, 12, 31)) { correct++; }
    if (date.firstDayOfYear(d) == date.yearStart(1999) &&
            date.lastDayOfYear(d) == date.yearStart(2000) - 1) { correct++; }
    if (date.yearStart(2025) - date.yearStart(2024) == 366 &&
            date.dayOfWeek(date.yearStart(2000)) == 6) { correct++; }

    /* Parsing into a CDate and back. */
    c = date.parse("2024-02-29 13:45:07", "%Y-%m-%d %H:%M:%s");
    date.toParts(&ds, c);
    if (ds.year == 2024 && ds.mon == 2 && ds.dayOfMon == 29 &&
            ds.hour == 13 && ds.min == 45 && ds.sec == 7) { correct++; }

    /* Now() agrees with the libc broken-down local time. Read it on
     * both sides of date.nowParts so an hour rolling over in between
     * can't fail the test. */
    date.Parts ds2;
    date.toParts(&ds, date.now());
    date.nowParts(&nowDs);
    date.toParts(&ds2, date.now());
    if (sameHour(&ds, &nowDs) || sameHour(&ds2, &nowDs)) { correct++; }
    if (ds.year >= 2024 && ds.min < 60 && ds.sec < 60) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
