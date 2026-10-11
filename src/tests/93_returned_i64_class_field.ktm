import date, mem, test;

/* Reading a field straight off an intrinsic class value with no storage
 * of its own - `Now().date`, `MkDate(..).time`, `raw(CDate).date` - used
 * the value (which comes back in a register, like an i64) as the address
 * of the class and crashed. */

u16 struct P16 { u8 lo; i8 hi; };

date.Date mkDate(i32 dateVal, u32 time) {
    date.Date c;
    c.date = dateVal;
    c.time = time;
    return c;
}

P16 mkP(u8 lo, i8 hi) {
    P16 p;
    p.lo = lo;
    p.hi = hi;
    return p;
}

i64 twice(i64 x) { return x * 2; }

i32 main() {
    i64 correct = 0, tests = 12, i;

    printf("Test - read a field off a returned i64 class: ");

    if (mkDate(-7, 99).date == -7) { correct++; }
    if (mkDate(-7, 99).time == 99) { correct++; }
    /* time is a u32: no sign extension. */
    if (mkDate(1, 0xFFFFFFF0).time == 0xFFFFFFF0) { correct++; }
    if (mkDate(0x12345678, 5).date == 0x12345678) { correct++; }

    fn(i32 dateVal, u32 time) date.Date fp = &mkDate;
    if (fp(-8, 98).date == -8 && fp(-8, 98).time == 98) { correct++; }

    /* Cast results are values too. */
    i64 raw = 0xFFFFFFF900000063;
    if (((date.Date)raw).date == -7 && ((date.Date)raw).time == 99) { correct++; }

    /* In expressions, arguments and loops. */
    if (twice(mkDate(21, 0).date) == 42) { correct++; }
    i64 tot = 0;
    for (i = 0; i < 5; i++) {
        tot += mkDate(i, i * 2).date + mkDate(i, i * 2).time;
    }
    if (tot == 30) { correct++; }

    /* A narrower intrinsic class. */
    if (mkP(250, -4).lo == 250 && mkP(250, -4).hi == -4) { correct++; }

    /* The stdlib's Now() / date.fromParts(). */
    date.Date n = date.now();
    if (date.now().date >= n.date && date.now().date - n.date <= 1) { correct++; }
    date.Parts ds;
    mem.set(&ds, 0, sizeof(date.Parts));
    ds.year = 2024;
    ds.mon = 2;
    ds.dayOfMon = 29;
    ds.hour = 13;
    date.Date d = date.fromParts(&ds);
    if (date.fromParts(&ds).date == d.date && date.fromParts(&ds).time == d.time) {
        correct++;
    }

    /* Copying first still works. */
    date.Date c = mkDate(3, 4);
    if (c.date == 3 && c.time == 4) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
