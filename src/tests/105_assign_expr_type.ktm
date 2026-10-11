import test;

import date, mem;

/* An assignment used as a value has the type of its left-hand side (as in
 * C). It used to be typed i64 for an i64-sized class, so `(c = Mk()).date`
 * failed with "Expected as class", and a by-value class assignment could not
 * have a field read from it either. */

struct Big {
    i64 a, b, c;
};

struct Pt {
    i64 x, y;
};

date.Date gDate;
i64 gIdx = 0;

date.Date mkDate(i32 date, u32 time) {
    date.Date d;
    d.date = date;
    d.time = time;
    return d;
}

Big mkBig() {
    Big b;
    b.a = 1;
    b.b = 2;
    b.c = 3;
    return b;
}

i64 nextFn() {
    gIdx++;
    if (gIdx > 3) { return 0; }
    return gIdx;
}

i32 main() {
    i64 correct = 0, tests = 18;

    printf("Test - assignment used as a value: ");

    /* i64-sized (intrinsic) class */
    date.Date c;
    if ((c = mkDate(7, 9)).date == 7) { correct++; }
    if (c.date == 7 && c.time == 9) { correct++; }
    if ((c = mkDate(-3, 11)).time == 11 && c.date == -3) { correct++; }
    if ((gDate = mkDate(5, 6)).date + gDate.time == 11) { correct++; }
    date.Date *pc = mem.alloc(sizeof(date.Date));
    if ((*pc = mkDate(1, 2)).time == 2 && pc.date == 1) { correct++; }
    mem.free(pc);
    date.Date d1, d2;
    d1 = d2 = mkDate(4, 8);
    if (d1.date == 4 && d2.time == 8) { correct++; }

    /* Larger by-value class */
    Big x, y;
    y.a = 4;
    y.b = 5;
    y.c = 6;
    if ((x = mkBig()).b == 2 && x.c == 3) { correct++; }
    if ((x = y).c == 6 && x.a == 4) { correct++; }

    /* Scalars */
    i64 a, b, i3;
    a = b = i3 = 5;
    if (a == 5 && b == 5 && i3 == 5) { correct++; }
    f64 f;
    f64 g = (f = 1.5) * 2;
    if (g == 3.0) { correct++; }
    f32 f32_;
    if ((f32_ = 1.5) * 2 == 3.0) { correct++; }
    i64 i;
    f64 h = (i = 2.7) * 1.5;
    if (h == 3.0 && i == 2) { correct++; }
    i64 n = 3;
    if ((n += 2) * 10 == 50 && n == 5) { correct++; }

    /* Pointers and fields */
    Pt pt, *pp = &pt, *q;
    pt.x = 1;
    if ((pp.x += 2) == 3) { correct++; }
    if ((pt.y = 40) + 2 == 42) { correct++; }
    if ((q = pp).x == 3 && q == pp) { correct++; }

    /* Conditions */
    i64 v, sum = 0;
    while ((v = nextFn())) { sum += v; }
    if (sum == 6) { correct++; }
    u8 *s = "abc", *t;
    i64 len = 0;
    while (*(t = s + len)) { len++; }
    if (len == 3) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
