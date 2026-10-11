#include "testhelper.ktm"

/* Passing a struct field read through a pointer (`p->field`) BY VALUE,
 * including alongside a struct copy into the same object.
 *
 * Two regressions:
 *  - a class-typed field read decayed to a scalar load of its first 8 bytes
 *    instead of its address, so the by-value-arg lowering dereferenced
 *    garbage (bus error);
 *  - once it decayed to its address, the by-value tagging mutated the shared
 *    operand pointer (`e` in `e->color`), which was ALSO the destination of
 *    the earlier `e->color = src` copy - corrupting that copy so it produced
 *    zeros. The fix returns a value private to the field read and keeps it
 *    out of the algebraic-identity fold while tagged. */

struct Color { i32 r, g, b, a; };
struct E     { Color color; };

Color DARKGRAY = {80, 80, 80, 255};

i64 sumColor(Color c) { return c.r + c.g + c.b + c.a; }

bool fieldByValueAfterCopy() {
    E storage;
    E *e = &storage;
    e.color = DARKGRAY;                  /* struct copy: global -> p->field */
    bool ok = e.color.r == 80 && e.color.g == 80 &&
                        e.color.b == 80 && e.color.a == 255;
    ok = ok && sumColor(e.color) == 495; /* pass p->field by value */
    ok = ok && sumColor(DARKGRAY) == 495; /* global directly (control) */
    return ok;
}

bool fieldByValueNoCopy() {
    E storage;
    E *e = &storage;
    e.color.r = 1; e.color.g = 2; e.color.b = 3; e.color.a = 4;
    return sumColor(e.color) == 10;
}

bool twoFieldArgs() {
    E s1, s2;
    E *a = &s1, *b = &s2;
    a.color = DARKGRAY;
    b.color.r = 1; b.color.g = 1; b.color.b = 1; b.color.a = 1;
    return sumColor(a.color) == 495 && sumColor(b.color) == 4;
}

i32 main() {
    printf("Test - passing a struct field (p->field) by value:\n");
    i64 tests = 3, correct = 0;
    if (fieldByValueAfterCopy()) { correct++; }
    if (fieldByValueNoCopy())    { correct++; }
    if (twoFieldArgs())          { correct++; }
    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
