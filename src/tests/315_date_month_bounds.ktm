import date, mem, test;

/* date.fromParts indexed its month table with mon-1 unchecked: month 13
 * read past the table (January of the same year), 0 read before it.
 * A month outside 1-12 now carries into the year, the way a day past
 * the end of a month carries into the next month. date.toParts read
 * mon_start_days[k+1] before checking k<11. */

date.Date mkDate(i64 y, i64 mo, i64 d) {
    date.Parts ds;
    mem.set(&ds, 0, sizeof(date.Parts));
    ds.year = y;
    ds.mon = mo;
    ds.dayOfMon = d;
    return date.fromParts(&ds);
}

bool same(i64 y1, i64 m1, i64 d1, i64 y2, i64 m2, i64 d2) {
    date.Date a = mkDate(y1, m1, d1), b = mkDate(y2, m2, d2);
    if (a.date == b.date) { return true; }
    printf("%d-%d-%d is day %d, want %d-%d-%d (%d)\n", y1, m1, d1, a.date,
        y2, m2, d2, b.date);
    return false;
}

bool is(date.Date c, i64 y, i64 mo, i64 d) {
    date.Parts ds;
    date.toParts(&ds, c);
    return ds.year == y && ds.mon == mo && ds.dayOfMon == d;
}

i32 main() {
    i64 correct = 0, tests = 10;
    printf("Test - CDate month bounds: ");

    if (same(2023, 13, 1, 2024, 1, 1)) { correct++; }
    if (same(2024, 14, 29, 2025, 3, 1)) { correct++; }  /* Feb 2025 has 28 */
    if (same(2024, 0, 1, 2023, 12, 1)) { correct++; }
    if (same(2024, -1, 15, 2023, 11, 15)) { correct++; }
    if (same(2024, 25, 1, 2026, 1, 1)) { correct++; }
    if (same(2024, -11, 1, 2023, 1, 1)) { correct++; }
    if (same(2024, -12, 1, 2022, 12, 1)) { correct++; }
    /* Days already carried: Jan 32 is Feb 1 */
    if (same(2024, 1, 32, 2024, 2, 1)) { correct++; }

    /* December, the last table entry, in a common and a leap year */
    if (is(mkDate(2023, 12, 31), 2023, 12, 31) &&
            is(mkDate(2023, 12, 1), 2023, 12, 1)) { correct++; }
    if (is(mkDate(2024, 12, 31), 2024, 12, 31) &&
            is(mkDate(2024, 11, 30), 2024, 11, 30)) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
