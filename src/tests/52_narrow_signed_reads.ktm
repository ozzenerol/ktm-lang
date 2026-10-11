import test;

/* Narrow signed integer reads must sign-extend on load (C integer promotion):
 * a signed i8/i16 read in an expression widens to 64-bit with its sign. The
 * IR is signedness-lossy, so the builder emits SEXT/ZEXT at every read that
 * loads a scalar through an address.
 *
 * Regression for a pre-existing hole: only plain scalar *local* variable
 * reads promoted; scalar globals, global AND local class fields, array
 * elements and pointer derefs did not - so a signed i8 of -1 read full-width
 * (==, "%d", widening into a wider slot) came back as 255. Unsigned narrows
 * are checked too, to confirm they still zero-extend. */

struct S { i8 a; i16 b; i32 c; u8 ua; u16 ub; };

i8  gI8;
i16 gI16;
u8  gU8;
u16 gU16;
S   gS;
i8  gI8arr[3];
i16 gI16arr[3];

bool scalarGlobals() {
    gI8 = -1; gI16 = -1000; gU8 = 200; gU16 = 50000;
    return gI8 == -1 && gI16 == -1000 && gU8 == 200 && gU16 == 50000;
}

bool globalFields() {
    gS.a = -5; gS.b = -2000; gS.c = -100000; gS.ua = 200; gS.ub = 50000;
    return gS.a == -5 && gS.b == -2000 && gS.c == -100000 &&
                 gS.ua == 200 && gS.ub == 50000;
}

bool globalArrays() {
    gI8arr[0]  = -3;   gI8arr[1]  = -7;   gI8arr[2]  = 100;
    gI16arr[0] = -300; gI16arr[1] = 4000; gI16arr[2] = -5000;
    return gI8arr[0]  == -3   && gI8arr[1]  == -7   && gI8arr[2]  == 100 &&
                 gI16arr[0] == -300 && gI16arr[1] == 4000 && gI16arr[2] == -5000;
}

bool localFieldsAndArrays() {
    S sl; sl.a = -11; sl.b = -3000; sl.ua = 250;
    i8 la[3]; la[0] = -9; la[1] = 5; la[2] = -1;
    return sl.a == -11 && sl.b == -3000 && sl.ua == 250 &&
                 la[0] == -9 && la[1] == 5 && la[2] == -1;
}

bool pointerDeref() {
    i8  v = -42;   i8  *p = &v;
    i16 w = -1234; i16 *q = &w;
    return *p == -42 && *q == -1234;
}

bool widenIntoWiderSlot() {
    /* The sign must survive widening into a wider slot, not only comparison. */
    gI8 = -1;     i64 x = gI8;
    gI16 = -1000; i64 y = gI16;
    gS.a = -5;    i64 z = gS.a;
    return x == -1 && y == -1000 && z == -5;
}

i32 main() {
    printf("Test - narrow signed integer reads sign-extend (all read paths):\n");
    i64 tests = 6, correct = 0;
    if (scalarGlobals())        { correct++; }
    if (globalFields())         { correct++; }
    if (globalArrays())         { correct++; }
    if (localFieldsAndArrays()) { correct++; }
    if (pointerDeref())         { correct++; }
    if (widenIntoWiderSlot())   { correct++; }
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
