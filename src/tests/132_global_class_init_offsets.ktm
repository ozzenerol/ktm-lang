import str, test;

/* A global class initialiser wrote its items back to back, ignoring the
 * field offsets: `P gp = {1,2,3,4}` with `i32 a; i64 b;` put `b` at
 * offset 4 instead of 8, so `gp.b` read garbage (and a string field
 * read as a pointer crashed). Each item must sit at its field's offset,
 * padded to the class size, in nested classes and arrays of classes
 * too. Locals were already right, so each global is compared with a
 * local initialised the same way. */

struct P132 {
    i32 a;
    i64 b;
    u8 c;
    i16 d;
};

struct Mix132 {
    u8 x;
    f64 f;
    u8 *s;
    f32 g;
    i16 h;
    u8 last;
};

struct Nest132 {
    u8 tag;
    P132 p;
    i32 tail;
};

struct Arr132 {
    u8 k;
    i32 v[3];
    i64 w;
};

struct Deep132 {
    i16 id;
    P132 ps[2];
    Mix132 m;
    u8 end;
};

P132    gP   = {1, 2, 3, 4};
u8      gGap = 0x5A;
P132    gPa[3] = {{5, 6, 7, 8}, {9, 10, 11, 12}, {-1, -2, 255, -4}};
Mix132  gMix = {1, 2.5, "hi", 3.5, -7, 200};
Nest132 gN   = {42, {1, 2, 3, 4}, 99};
Arr132  gArr = {7, {10, 20, 30}, 40};
Deep132 gD   = {3, {{1, 2, 3, 4}, {5, 6, 7, 8}},
                                 {9, -1.25, "deep", 0.5, 11, 12}, 13};
/* Scalar arrays were laid out right before and must stay so. */
i32     gI32[4] = {1, -2, 3, -4};
u8      gU8[5]  = {1, 2, 3, 4, 5};
i16     gI16[3] = {-1, 2, -3};
f32     gF32[2] = {1.5, -2.25};
f64     gF64[2] = {0.125, 8.5};
u8     *gStrs[2] = {"one", "two"};
i64     gAfter = 0x1122334455667788;

bool pEq(P132 *p, i64 a, i64 b, i64 c, i64 d) {
    return p.a == a && p.b == b && p.c == c && p.d == d;
}

bool mixEq(Mix132 *m, i64 x, f64 f, u8 *s, f64 g, i64 h, i64 last) {
    return m.x == x && m.f == f && !str.cmp(m.s, s) && m.g == g &&
                 m.h == h && m.last == last;
}

/* The same bytes as a local initialised the same way, padding aside. */
bool sameP(P132 *a, P132 *b) {
    return pEq(a, b.a, b.b, b.c, b.d);
}

i32 main() {
    printf("Test - global class initialiser field offsets: ");
    i64 correct = 0, tests = 24;

    P132 lP = {1, 2, 3, 4};
    if (pEq(&gP, 1, 2, 3, 4)) { correct++; }
    if (sameP(&gP, &lP)) { correct++; }
    if (gGap == 0x5A) { correct++; }

    if (pEq(&gPa[0], 5, 6, 7, 8)) { correct++; }
    if (pEq(&gPa[1], 9, 10, 11, 12)) { correct++; }
    if (pEq(&gPa[2], -1, -2, 255, -4)) { correct++; }
    /* Elements are sizeof apart. */
    if ((u8 *)(&gPa[1]) - (u8 *)(&gPa[0]) == sizeof(P132)) { correct++; }

    if (mixEq(&gMix, 1, 2.5, "hi", 3.5, -7, 200)) { correct++; }

    if (gN.tag == 42 && pEq(&gN.p, 1, 2, 3, 4) && gN.tail == 99) { correct++; }
    Nest132 lN = {42, {1, 2, 3, 4}, 99};
    if (lN.tail == gN.tail && sameP(&lN.p, &gN.p)) { correct++; }

    if (gArr.k == 7 && gArr.v[0] == 10 && gArr.v[1] == 20 &&
            gArr.v[2] == 30 && gArr.w == 40) { correct++; }

    if (gD.id == 3 && pEq(&gD.ps[0], 1, 2, 3, 4) &&
            pEq(&gD.ps[1], 5, 6, 7, 8)) { correct++; }
    if (mixEq(&gD.m, 9, -1.25, "deep", 0.5, 11, 12)) { correct++; }
    if (gD.end == 13) { correct++; }

    if (gI32[0] == 1 && gI32[1] == -2 && gI32[2] == 3 && gI32[3] == -4) {
        correct++;
    }
    if (gU8[0] == 1 && gU8[2] == 3 && gU8[4] == 5) { correct++; }
    if (gI16[0] == -1 && gI16[1] == 2 && gI16[2] == -3) { correct++; }
    if (gF32[0] == 1.5 && gF32[1] == -2.25) { correct++; }
    if (gF64[0] == 0.125 && gF64[1] == 8.5) { correct++; }
    if (!str.cmp(gStrs[0], "one") && !str.cmp(gStrs[1], "two")) { correct++; }
    if (gAfter == 0x1122334455667788) { correct++; }

    /* Function statics take the same path. */
    static P132 sP = {21, 22, 23, 24};
    if (pEq(&sP, 21, 22, 23, 24)) { correct++; }
    static Mix132 sM[2] = {{1, 1.0, "a", 2.0, 3, 4}, {5, 6.0, "b", 7.0, 8, 9}};
    if (mixEq(&sM[0], 1, 1.0, "a", 2.0, 3, 4)) { correct++; }
    if (mixEq(&sM[1], 5, 6.0, "b", 7.0, 8, 9)) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
