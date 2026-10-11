import test;

import date;

/* Reading a whole intrinsic class value (`i64 class CDate`) through a
 * pointer - `pp[i]`, `*pp`, `*(pp+2)` - gave the address instead of the
 * class contents, because the deref was treated like that of an ordinary
 * (by-address) class. Plain classes of any size must keep working. */

u16 struct P16 { u8 lo; i8 hi; };
i64 struct One { i64 v; };
struct Plain8 { i32 a; i32 b; };
struct Plain16 { i64 a; i64 b; };
struct Plain24 { i64 a; i64 b; i64 c; };

date.Date gdates[3];

i64 sum(date.Date c) { return c.date * 1000 + c.time; }
date.Date first(date.Date *p) { return *p; }
/* (index first: `(T *p, i64 i) { return p[i]; }` hits a separate arm64
 * AOT bug that also affects i64 pointers) */
date.Date at(i64 i, date.Date *p) { return p[i]; }

i32 main() {
    i64 correct = 0, tests = 24, i;

    printf("Test - read a whole i64 class through a pointer: ");

    date.Date a[3];
    for (i = 0; i < 3; i++) { a[i].date = 10 * i + 1; a[i].time = 10 * i + 2; }
    date.Date *pp = a;

    date.Date d = pp[2];
    if (d.date == 21 && d.time == 22) { correct++; }
    date.Date e = *pp;
    if (e.date == 1 && e.time == 2) { correct++; }
    date.Date f = *(pp + 2);
    if (f.date == 21 && f.time == 22) { correct++; }
    date.Date g = a[1];
    if (g.date == 11 && g.time == 12) { correct++; }

    /* Assigning into an existing variable. */
    d = pp[1];
    if (d.date == 11 && d.time == 12) { correct++; }
    d = *pp;
    if (d.date == 1 && d.time == 2) { correct++; }

    /* By-value argument and return value. */
    if (sum(pp[2]) == 21022) { correct++; }
    if (sum(*pp) == 1002) { correct++; }
    date.Date r = first(pp + 1);
    if (r.date == 11 && r.time == 12) { correct++; }
    if (at(2, pp).date == 21 && at(2, pp).time == 22) { correct++; }

    /* Fields, addresses and writes through the pointer are unchanged. */
    if (pp[2].date == 21 && (*pp).time == 2 && pp.date == 1) { correct++; }
    if (&pp[2] == &a[2] && (u8 *)&pp[2] - (u8 *)pp == 16) { correct++; }
    pp[0] = pp[2];
    if (a[0].date == 21 && a[0].time == 22) { correct++; }
    *(pp + 1) = g;
    if (a[1].date == 11 && a[1].time == 12) { correct++; }
    /* Unsigned time keeps all 32 bits, signed date its sign. */
    a[2].date = -5; a[2].time = 0xFFFFFFF0;
    d = pp[2];
    if (d.date == -5 && d.time == 0xFFFFFFF0) { correct++; }

    /* Global array through a pointer. */
    gdates[1].date = 7; gdates[1].time = 8;
    date.Date *gp = gdates;
    d = gp[1];
    if (d.date == 7 && d.time == 8) { correct++; }

    /* An i64 class with a single i64 field. */
    One o[3]; o[2].v = 0x123456789A;
    One *op = o;
    One ov = op[2];
    if (ov.v == 0x123456789A) { correct++; }
    op = &o[2];
    ov = *op;
    if (ov.v == 0x123456789A) { correct++; }

    /* A narrower intrinsic class. */
    P16 ps[2]; ps[1].lo = 200; ps[1].hi = -3;
    P16 *psp = ps;
    P16 pv = psp[1];
    if (pv.lo == 200 && pv.hi == -3) { correct++; }

    /* Ordinary classes of 8, 16 and 24 bytes. */
    Plain8 p8[2]; p8[1].a = 3; p8[1].b = 4; Plain8 *p8p = p8;
    Plain8 v8 = p8p[1];
    if (v8.a == 3 && v8.b == 4) { correct++; }
    Plain16 p16[2]; p16[1].a = 5; p16[1].b = 6; Plain16 *p16p = p16;
    Plain16 v16 = p16p[1];
    if (v16.a == 5 && v16.b == 6) { correct++; }
    Plain24 p24[2]; p24[1].a = 7; p24[1].b = 8; p24[1].c = 9;
    Plain24 *p24p = p24;
    Plain24 v24 = *(p24p + 1);
    if (v24.a == 7 && v24.b == 8 && v24.c == 9) { correct++; }

    /* Integer pointers are unchanged. */
    i64 ints[3] = {4, 5, 6}, *ip = ints;
    if (ip[2] == 6 && *ip == 4 && *(ip + 1) == 5) { correct++; }
    i32 i32s[3] = {-1, -2, -3}, *i32p = i32s;
    if (i32p[2] == -3 && *i32p == -1) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
