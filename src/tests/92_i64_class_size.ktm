#include "testhelper.ktm"

import date, mem, str;

/* An intrinsic class (`i64 class CDate { u32 time; i32 date; };`) is a
 * value of its base integer type whose fields overlay its bytes.
 * sizeof(CDate) was 16 (every intrinsic class was), so arrays
 * of them had a 16-byte stride and classes holding one were padded. It is
 * now the base type's size: i64 class -> 8, i32 class -> 4, u16 -> 2... */

u16 struct P16 { u8 lo; u8 hi; };
i16 struct S16 { u8 lo; i8 hi; };
i32 struct S32 { u16 lo; i16 hi; };
u8 struct B8 { u8 v; };

struct Holder { i32 a; date.Date d; i32 b; };
struct Q { i32 a; i32 b; };
struct Packed { u8 pre; P16 p; u8 mid; S32 s; u8 post; S16 n; i64 tail; };

date.Date mkDate(i32 date, u32 time) {
    date.Date c;
    c.date = date;
    c.time = time;
    return c;
}

i64 dateSum(date.Date c, i64 k) { return c.date * 10 + c.time + k; }

P16 mkP(u8 lo, u8 hi) {
    P16 p;
    p.lo = lo;
    p.hi = hi;
    return p;
}

i64 useNarrow(P16 p, S16 n) { return p.hi * 1000 + p.lo * 10 + n.hi; }

date.Date gDates[3];
S32 gS32;

i32 main() {
    i64 correct = 0, tests = 19, i;

    printf("Test - sizeof of an intrinsic class is its base type's size: ");

    if (sizeof(date.Date) == 8) { correct++; }
    if (sizeof(P16) == 2 && sizeof(S16) == 2 && sizeof(S32) == 4 &&
            sizeof(B8) == 1) { correct++; }
    if (sizeof(Holder) == 24) { correct++; }

    /* Arrays: 8-byte stride, elements don't overlap. */
    date.Date arr[4];
    for (i = 0; i < 4; i++) {
        arr[i] = mkDate(i + 100, i * 7);
    }
    if ((u8 *)(&arr[1]) - (u8 *)(&arr[0]) == 8) { correct++; }
    i64 ok = 1;
    for (i = 0; i < 4; i++) {
        if (arr[i].date != i + 100 || arr[i].time != i * 7) { ok = 0; }
    }
    if (ok) { correct++; }

    /* Same shape with an ordinary 8-byte class: a 4-byte field of an
     * element with an 8-byte stride (on arm64 the index can't be folded
     * into the load as `lsl #3`). */
    Q qs[4];
    i64 qsum = 0;
    for (i = 0; i < 4; i++) {
        qs[i].a = i;
        qs[i].b = -i * 10;
    }
    for (i = 0; i < 4; i++) { qsum += qs[i].b * 100 + qs[i].a; }
    if (qsum == -5994) { correct++; }

    /* The fields overlay the i64: time is the low half, date the high. */
    date.Date c = mkDate(7, 8);
    if ((i64)c == 0x700000008) { correct++; }
    c = mkDate(-5, 1234);
    if (c.date == -5 && c.time == 1234) { correct++; }

    /* A CDate inside another class, passed by value. */
    Holder h;
    h.a = 11;
    h.b = 22;
    h.d = mkDate(-3, 99);
    if ((u8 *)(&h.d) - (u8 *)(&h) == 8 &&
            (u8 *)(&h.b) - (u8 *)(&h) == 16) { correct++; }
    if (h.a == 11 && h.b == 22 && h.d.date == -3 && h.d.time == 99) { correct++; }
    if (dateSum(h.d, 5) == -30 + 99 + 5) { correct++; }
    Holder h2 = h;
    Holder *hp = &h2;
    if (hp.d.date == -3 && hp.d.time == 99 && hp.b == 22) { correct++; }

    /* Global array. */
    gDates[2] = mkDate(42, 43);
    if (gDates[2].date == 42 && gDates[2].time == 43 &&
            gDates[1].date == 0 && gDates[1].time == 0) { correct++; }

    /* Narrow intrinsic classes: stores don't clobber the neighbours and
     * reads widen per the base type's signedness. */
    Packed b;
    b.pre = 0xAA;
    b.mid = 0xBB;
    b.post = 0xCC;
    b.tail = 0x1122334455667788;
    b.p = mkP(3, 4);
    b.s.lo = 0xFFFF;
    b.s.hi = -2;
    b.n.lo = 5;
    b.n.hi = -3;
    if (b.pre == 0xAA && b.mid == 0xBB && b.post == 0xCC &&
            b.tail == 0x1122334455667788) { correct++; }
    P16 q = b.p;
    S16 n = b.n;
    S32 s = b.s;
    if ((i64)q == 0x0403 && (i64)n == -763 && (i64)s == -65537) { correct++; }
    if (useNarrow(q, n) == 4000 + 30 - 3) { correct++; }
    gS32 = s;
    if (gS32.lo == 0xFFFF && gS32.hi == -2 && (i64)gS32 == -65537) { correct++; }
    B8 bb;
    bb.v = 200;
    if ((i64)bb == 200) { correct++; }

    /* The stdlib (built by ktm with the same layout) agrees. */
    date.Parts ds;
    date.Parts out;
    mem.set(&ds, 0, sizeof(date.Parts));
    ds.year = 2024;
    ds.mon = 2;
    ds.dayOfMon = 29;
    ds.hour = 13;
    ds.min = 14;
    ds.sec = 15;
    date.Date d = date.fromParts(&ds);
    date.toParts(&out, d);
    if (out.year == 2024 && out.mon == 2 && out.dayOfMon == 29 &&
            out.hour == 13 && out.min == 14 && out.sec == 15 &&
            !str.cmp(date.formatDate(d), "02/29/2024") &&
            !str.cmp(date.formatTime(d), "13:14:15")) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
