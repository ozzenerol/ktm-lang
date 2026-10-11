import cstr, date, mem, test;

/* date.toParts estimates the year from the day number and only corrected
 * an estimate that was too high. For 182 years in 1..10000 (1903,
 * 1904..1932 every 4 years, 2304..2320, ...) the estimate for Jan 1 was
 * one too low and the day came out as Dec 32 of the year before. */

date.Date mkDay(i64 y, i64 mo, i64 d) {
    date.Parts ds;
    mem.set(&ds, 0, sizeof(date.Parts));
    ds.year = y;
    ds.mon = mo;
    ds.dayOfMon = d;
    return date.fromParts(&ds);
}

bool is(date.Date c, i64 y, i64 mo, i64 d) {
    date.Parts ds;
    date.toParts(&ds, c);
    if (ds.year == y && ds.mon == mo && ds.dayOfMon == d) { return true; }
    printf("got %d-%d-%d want %d-%d-%d\n", ds.year, ds.mon, ds.dayOfMon, y, mo, d);
    return false;
}

/* Jan 1 of `y` and the day before it */
bool yearEdge(i64 y) {
    date.Date c = mkDay(y, 1, 1);
    if (!is(c, y, 1, 1)) { return false; }
    c.date--;
    return is(c, y - 1, 12, 31);
}

i32 main() {
    i64 correct = 0, tests = 7, y, d, bad;
    date.Parts ds, prev;
    date.Date c = 0;
    printf("Test - Date2Struct at the start of a year: ");

    if (yearEdge(1903)) { correct++; }
    if (yearEdge(1904)) { correct++; }

    bad = 0;
    for (y = 1904; y <= 1932; y += 4) { if (!yearEdge(y)) { bad++; } }
    if (!bad) { correct++; }

    bad = 0;
    for (y = 2304; y <= 2320; y += 4) { if (!yearEdge(y)) { bad++; } }
    if (!bad) { correct++; }

    /* Every year boundary from 1 to 10000 */
    bad = 0;
    for (y = 2; y <= 10000; y++) { if (!yearEdge(y)) { bad++; } }
    if (!bad) { correct++; }
    else { printf("%d bad years\n", bad); }

    /* Every day 1890..2410: round trips and follows the day before */
    bad = 0;
    c.date = mkDay(1890, 1, 1).date;
    date.toParts(&prev, c);
    for (d = c.date + 1; d < mkDay(2410, 1, 1).date; d++) {
        c.date = d;
        date.toParts(&ds, c);
        if (date.fromParts(&ds).date != d) { bad++; }
        if (ds.dayOfMon < 1 || ds.dayOfMon > 31 || ds.mon < 1 || ds.mon > 12) {
            bad++;
        }
        if (ds.dayOfMon == 1) {
            if (!(ds.mon == prev.mon + 1 && ds.year == prev.year) &&
                    !(ds.mon == 1 && prev.mon == 12 && ds.year == prev.year + 1)) {
                bad++;
                    }
        } else if (ds.dayOfMon != prev.dayOfMon + 1 || ds.mon != prev.mon) {
            bad++;
        }
        prev = ds;
    }
    if (!bad) { correct++; }
    else { printf("%d bad days\n", bad); }

    /* date.formatDate goes through date.toParts */
    u8 *s = date.formatDate(mkDay(1904, 1, 1));
    if (!cstr.cmp(s, "01/01/1904")) { correct++; }
    else { printf("MPrintDate: %s\n", s); }
    mem.free(s);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
