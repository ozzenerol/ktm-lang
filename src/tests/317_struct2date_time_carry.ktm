import cstr, date, mem, test;

/* date.fromParts did not carry the time past a day: the time of day wrapped
 * and the date stayed, so 2024-03-05 with hour 24, min 30 gave
 * 03/05/2024 00:30:00. Hours, minutes, seconds, sec100 and sec10000 now
 * carry into the next units and into the date, negative values borrow
 * from the day before. */

date.Date mk(i64 y, i64 mo, i64 d, i64 h, i64 mi, i64 s, i64 s100 = 0,
                 i64 s10000 = 0) {
    date.Parts ds;
    mem.set(&ds, 0, sizeof(date.Parts));
    ds.year = y;
    ds.mon = mo;
    ds.dayOfMon = d;
    ds.hour = h;
    ds.min = mi;
    ds.sec = s;
    ds.sec100 = s100;
    ds.sec10000 = s10000;
    return date.fromParts(&ds);
}

i64 gCorrect = 0, gTests = 0;

void expect(date.Date c, u8 *want) {
    gTests++;
    u8 *got = date.format(c, "%m/%d/%Y %H:%M:%S").take();
    if (!cstr.cmp(got, want)) { gCorrect++; }
    else { printf("Failed - got '%s' want '%s'\n", got, want); }
    mem.free(got);
}

void expectSame(date.Date got, date.Date want, u8 *what) {
    gTests++;
    if ((i64)got == (i64)want) { gCorrect++; }
    else { printf("Failed - %s: %X != %X\n", what, (i64)got, (i64)want); }
}

i32 main() {
    printf("Test - Struct2Date carries the time into the date: ");
    expect(mk(2024, 3, 5, 24, 30, 0), "03/06/2024 00:30:00");
    expect(mk(2024, 3, 5, 25, 0, 0), "03/06/2024 01:00:00");
    expect(mk(2024, 3, 5, 48, 0, 0), "03/07/2024 00:00:00");
    expect(mk(2024, 3, 5, 23, 60, 0), "03/06/2024 00:00:00");
    expect(mk(2024, 3, 5, 12, 90, 0), "03/05/2024 13:30:00");
    expect(mk(2024, 3, 5, 0, 1440, 0), "03/06/2024 00:00:00");
    expect(mk(2024, 3, 5, 23, 59, 60), "03/06/2024 00:00:00");
    expect(mk(2024, 3, 5, 0, 0, 86400 + 61), "03/06/2024 00:01:01");
    expect(mk(2024, 3, 5, 23, 59, 59, 100), "03/06/2024 00:00:00");
    expect(mk(2024, 3, 5, 23, 59, 59, 99, 100), "03/06/2024 00:00:00");
    /* Across the end of a month and a year, leap and common years */
    expect(mk(2023, 12, 31, 23, 59, 60), "01/01/2024 00:00:00");
    expect(mk(2023, 12, 31, 24, 0, 0), "01/01/2024 00:00:00");
    expect(mk(2024, 2, 28, 23, 59, 60), "02/29/2024 00:00:00");
    expect(mk(2023, 2, 28, 23, 59, 60), "03/01/2023 00:00:00");
    expect(mk(2024, 1, 31, 47, 0, 0), "02/01/2024 23:00:00");
    /* Negative values borrow from the day before */
    expect(mk(2024, 3, 5, -1, 0, 0), "03/04/2024 23:00:00");
    expect(mk(2024, 3, 5, -24, 0, 0), "03/04/2024 00:00:00");
    expect(mk(2024, 3, 5, -25, 0, 0), "03/03/2024 23:00:00");
    expect(mk(2024, 3, 5, 0, -60, 0), "03/04/2024 23:00:00");
    expect(mk(2024, 1, 1, 0, 0, -1), "12/31/2023 23:59:59");
    expect(mk(2024, 3, 1, 0, 0, 0, -1), "02/29/2024 23:59:59");
    expect(mk(2024, 3, 5, 1, -30, 0), "03/05/2024 00:30:00");
    /* The carried CDate is the same as the one built from in-range fields */
    expectSame(mk(2024, 3, 5, 24, 30, 0), mk(2024, 3, 6, 0, 30, 0), "24:30");
    expectSame(mk(2023, 12, 31, 23, 59, 59, 99, 100), mk(2024, 1, 1, 0, 0, 0),
                         "sec10000 carry");
    expectSame(mk(2024, 3, 5, 0, 0, 0, 0, 12345),
                         mk(2024, 3, 5, 0, 0, 1, 23, 45), "sec10000 into sec");
    expectSame(mk(2024, 3, 5, 0, 0, -1), mk(2024, 3, 4, 23, 59, 59), "-1 s");
    /* In-range times are unchanged */
    expect(mk(2024, 2, 29, 12, 34, 56), "02/29/2024 12:34:56");
    expect(mk(2024, 3, 5, 0, 0, 0), "03/05/2024 00:00:00");
    expect(mk(2024, 3, 5, 23, 59, 59, 99, 99), "03/05/2024 23:59:59");
    date.Parts ds;
    date.toParts(&ds, mk(2024, 3, 5, 23, 59, 59, 99));
    gTests++;
    if (ds.dayOfMon == 5 && ds.hour == 23 && ds.min == 59 && ds.sec == 59 &&
            ds.sec100 == 99) { gCorrect++; }
    else { printf("Failed - round trip of 23:59:59.99\n"); }
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
