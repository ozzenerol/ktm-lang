import test;

/* Returning a struct by value, per the platform C ABI. <=16-byte aggregates
 * come back in registers (AArch64: HFA->v0.., INTEGER->x0,x1; SysV: per
 * eightbyte INTEGER->rax,rdx / SSE->xmm0,xmm1); >16-byte aggregates use a
 * hidden out-pointer. Previously ktm used the hidden out-pointer for ALL
 * struct returns, which mismatched the C ABI at every boundary (e.g. calling
 * an `extern "c"` function that returns a small struct returned garbage). */

struct Vec2  { f32 x, y; };          /* 8B HFA / single SSE eightbyte */
struct Pt    { i32 a, b, c, d; };    /* 16B -> 2 INTEGER eightbytes */
struct Pair  { i64 a, b; };          /* 16B -> 2 INTEGER */
struct Mix   { i64 n; f64 f; };      /* 16B -> INTEGER + SSE */
struct Big   { i64 a, b, c; };       /* 24B -> indirect / MEMORY */

Vec2 makeVec2(f32 x, f32 y) { Vec2 v; v.x = x; v.y = y; return v; }
Pt   makePt(i32 n)          { Pt p; p.a=n; p.b=n+1; p.c=n+2; p.d=n+3; return p; }
Pair makePair(i64 a, i64 b) { Pair p; p.a = a; p.b = b; return p; }
Mix  makeMix(i64 n, f64 f)  { Mix m; m.n = n; m.f = f; return m; }
Big  makeBig(i64 n)         { Big b; b.a=n; b.b=n*2; b.c=n*3; return b; }

i64 sumPt(Pt p) { return p.a + p.b + p.c + p.d; }

bool hfaReturn() {
    Vec2 v = makeVec2(3.5, 7.5);
    return v.x == 3.5 && v.y == 7.5;
}

bool integerReturn() {
    Pt p = makePt(10);
    Pair q = makePair(100, 200);
    return p.a == 10 && p.b == 11 && p.c == 12 && p.d == 13 &&
                 q.a == 100 && q.b == 200;
}

bool mixedReturn() {
    Mix m = makeMix(42, 9.5);
    return m.n == 42 && m.f == 9.5;
}

bool indirectReturn() {
    Big b = makeBig(100);
    return b.a == 100 && b.b == 200 && b.c == 300;
}

bool directFieldAccess() {
    /* Field of a call result without binding it to a variable. */
    return makeVec2(1.5, 2.5).y == 2.5 && makePt(5).c == 7;
}

bool returnPassedAsArg() {
    /* A returned struct fed straight into another by-value parameter. */
    return sumPt(makePt(20)) == 86;   /* 20+21+22+23 */
}

bool returnIntoCopy() {
    Pt p;
    p = makePt(1);                    /* struct assignment from a call result */
    return p.a == 1 && p.d == 4;
}

i32 main() {
    printf("Test - returning a struct by value (C ABI):\n");
    i64 tests = 7, correct = 0;
    if (hfaReturn())          { correct++; }
    if (integerReturn())      { correct++; }
    if (mixedReturn())        { correct++; }
    if (indirectReturn())     { correct++; }
    if (directFieldAccess())  { correct++; }
    if (returnPassedAsArg())  { correct++; }
    if (returnIntoCopy())     { correct++; }
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
