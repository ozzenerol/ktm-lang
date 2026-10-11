import test;

import date, mem, str;

/* CDate used the original calendar: no leap year in years divisible by
 * 4000, and none in year 0. From 4000-03-01 every date was a day off from
 * the Gregorian calendar (4000-02-29 didn't exist), which StrToCDate and
 * IsoFromTimespec use. It is now the proleptic Gregorian calendar, with
 * year 0 a leap year, and day numbers of years 1 to 3999 are unchanged. */

bool isLeap(i64 y) {
    return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
}

i64 monLen(i64 y, i64 m) {
    if (m == 2) {
        if (isLeap(y)) { return 29; }
        return 28;
    }
    if (m == 4 || m == 6 || m == 9 || m == 11) { return 30; }
    return 31;
}

date.Date mkDay(i64 y, i64 mo, i64 d) {
    date.Parts ds;
    mem.set(&ds, 0, sizeof(date.Parts));
    ds.year = y;
    ds.mon = mo;
    ds.dayOfMon = d;
    return date.fromParts(&ds);
}

bool dayIs(i64 date, i64 y, i64 mo, i64 d, i64 dow) {
    date.Parts ds;
    date.Date c = 0;
    c.date = date;
    date.toParts(&ds, c);
    if (ds.year == y && ds.mon == mo && ds.dayOfMon == d &&
            ds.dayOfWeek == dow && mkDay(y, mo, d).date == date) {
        return true;
            }
    printf("day %d: got %d-%d-%d (%d), want %d-%d-%d (%d)\n", date, ds.year,
        ds.mon, ds.dayOfMon, ds.dayOfWeek, y, mo, d, dow);
    return false;
}

bool strIs(u8 *s, u8 *want) {
    bool ok = !str.cmp(s, want);
    if (!ok) { printf("got %s want %s\n", s, want); }
    mem.free(s);
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 10, y, m, d, n, bad, dow;
    date.Parts ds;
    date.Date c = 0;
    printf("Test - proleptic Gregorian calendar: ");

    /* Day numbers of years 1-3999 are what they were */
    if (date.yearStart(1) == 365 && date.yearStart(2000) == 730484 &&
            dayIs(365, 1, 1, 1, 1) && dayIs(730484, 2000, 1, 1, 6)) { correct++; }

    /* 4000 and 8000 are leap years, the days after them line up again */
    if (dayIs(1461028, 4000, 2, 29, 2) && dayIs(1461029, 4000, 3, 1, 3) &&
            dayIs(2921998, 8000, 2, 29, 2) && dayIs(3652423, 9999, 12, 31, 5)) {
        correct++;
            }

    /* Year 0 is a leap year and day 0 its Jan 2; years before it follow
     * the same 400-year rule */
    if (date.yearStart(0) == -1 && dayIs(-1, 0, 1, 1, 6) &&
            dayIs(0, 0, 1, 2, 0) && dayIs(58, 0, 2, 29, 2) &&
            dayIs(365, 1, 1, 1, 1)) { correct++; }

    bad = 0;
    for (y = -1200; y <= 10000; y++) {
        n = date.yearStart(y + 1) - date.yearStart(y);
        if ((isLeap(y) && n != 366) || (!isLeap(y) && n != 365)) {
            if (!bad) { printf("year %d has %d days\n", y, n); }
            bad++;
        }
    }
    if (!bad) { correct++; }

    /* Every day of years 1 to 9999 (and the leap year 0 and the years
     * before it): the next day number, the right weekday, both ways */
    bad = 0;
    n = date.yearStart(-401);
    dow = date.dayOfWeek(n);
    for (y = -401; y <= 9999; y++) {
        for (m = 1; m <= 12; m++) {
            for (d = 1; d <= monLen(y, m); d++) {
                c.date = n;
                date.toParts(&ds, c);
                if (ds.year != y || ds.mon != m || ds.dayOfMon != d ||
                        ds.dayOfWeek != dow || mkDay(y, m, d).date != n) {
                    if (!bad) { printf("%d-%d-%d: day %d came out %d-%d-%d\n", y, m, d, n,
                        ds.year, ds.mon, ds.dayOfMon);
                    }
                    bad++;
                }
                n++;
                dow = (dow + 1) % 7;
            }
        }
    }
    if (!bad) { correct++; }

    /* The parser, which always used Gregorian leap years, now agrees */
    c = date.parse("4000-02-29", "%Y-%m-%d");
    if (c.date == 1461028 && strIs(date.formatDate(c), "02/29/4000")) { correct++; }
    if (date.parse("4000-03-01", "%Y-%m-%d").date == 1461029 &&
            date.parse("0000-02-29", "%Y-%m-%d").date == 58 &&
            date.parse("9999-12-31", "%Y-%m-%d").date == 3652423) { correct++; }

    /* The month and year helpers */
    if (date.lastDayOfMonth(1461000) == 1461028 && date.firstDayOfMonth(1461028) == 1461000 &&
            date.lastDayOfYear(1461028) - date.firstDayOfYear(1461028) == 365 &&
            date.lastDayOfYear(0) == 364 && date.firstDayOfYear(364) == -1) { correct++; }

    /* Carrying across Feb 29 of 4000 */
    if (strIs(date.formatDate(mkDay(4000, 2, 30)), "03/01/4000") &&
            strIs(date.formatDate(mkDay(4000, 3, 0)), "02/29/4000")) { correct++; }

    if (strIs(date.formatDate(mkDay(4000, 12, 31)), "12/31/4000") &&
            mkDay(4001, 1, 1).date - mkDay(4000, 1, 1).date == 366) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
