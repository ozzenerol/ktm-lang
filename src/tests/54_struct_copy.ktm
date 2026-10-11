#include "testhelper.ktm"

/* By-value struct/class assignment and copy-initialisation: `dst = src` and
 * `T d = src;` between two values of the same class copy the bytes (memcpy),
 * not the address. Regression for a missing feature: `c = black` raised
 * "the `=` operator cannot be applied to a class", and `Color c = black;`
 * silently stored the source's *address* into the first 8 bytes (garbage).
 * Covers local/global/deref targets, struct-returning calls, chained
 * assignment, and that the copy is independent of the source. */

struct Color { f32 r, g, b; };
struct Pt    { i64 x, y; };

Color gSrc = {7.0, 8.0, 9.0};
Color gDst;

Color makeColor(f32 v) {
    Color c;
    c.r = v; c.g = v + 1.0; c.b = v + 2.0;
    return c;
}

bool localAssign() {
    Color black = {1.0, 2.0, 3.0};
    Color c;
    c = black;
    return c.r == 1.0 && c.g == 2.0 && c.b == 3.0;
}

bool declInitCopy() {
    Color black = {1.0, 2.0, 3.0};
    Color d = black;
    return d.r == 1.0 && d.g == 2.0 && d.b == 3.0;
}

bool globalInvolved() {
    Color black = {1.0, 2.0, 3.0};
    gDst = black;                      /* global = local */
    Color e;
    e = gSrc;                          /* local = global */
    return gDst.r == 1.0 && gDst.g == 2.0 && gDst.b == 3.0 &&
                 e.r == 7.0 && e.g == 8.0 && e.b == 9.0;
}

bool derefCopy() {
    Color black = {1.0, 2.0, 3.0};
    Color f;
    Color *pf = &f, *pb = &black;
    *pf = *pb;
    return f.r == 1.0 && f.g == 2.0 && f.b == 3.0;
}

bool callAndChained() {
    Color a;
    a = makeColor(5.0);                 /* assign from struct-returning call */
    Color src = {1.0, 2.0, 3.0};
    Color b, c;
    c = b = src;                        /* chained struct assignment */
    return a.r == 5.0 && a.g == 6.0 && a.b == 7.0 &&
                 b.r == 1.0 && b.b == 3.0 && c.r == 1.0 && c.b == 3.0;
}

bool independentCopy() {
    /* The copy must be a real value copy, not an alias: mutating the source
     * afterwards must not change the destination. */
    Pt p1; p1.x = 10; p1.y = 20;
    Pt p2;
    p2 = p1;
    p1.x = 99; p1.y = 88;
    return p2.x == 10 && p2.y == 20 && p1.x == 99 && p1.y == 88;
}

i32 main() {
    printf("Test - by-value struct/class copy and assignment:\n");
    i64 tests = 6, correct = 0;
    if (localAssign())     { correct++; }
    if (declInitCopy())    { correct++; }
    if (globalInvolved())  { correct++; }
    if (derefCopy())       { correct++; }
    if (callAndChained())  { correct++; }
    if (independentCopy()) { correct++; }
    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
