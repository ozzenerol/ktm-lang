import test;

/* Structs passed and returned BY VALUE, following the platform C ABI.
 * Exercises both sides (ktm callee + ktm caller): small integer
 * aggregates in GP regs, HFAs (f32/f64) in FP regs, >16-byte aggregates
 * by reference, mixed scalar/struct args (register accounting), and
 * struct returns. */

struct Color { u8 r; u8 g; u8 b; u8 a; };          /* 4B  INTEGER, 1 GP */
struct I2    { i64 a; i64 b; };                     /* 16B INTEGER, 2 GP */
struct Vec2  { f32 x; f32 y; };                     /* HFA, 2x s */
struct Rect  { f32 x; f32 y; f32 w; f32 h; };       /* HFA, 4x s */
struct V2d   { f64 a; f64 b; };                     /* HFA, 2x d */
struct Big   { i64 a; i64 b; i64 c; };              /* 24B, by reference */

i64 colorSum(Color c) { return c.r + c.g*10 + c.b*100 + c.a*1000; }
i64 i2Sum(I2 v)       { return v.a*10 + v.b; }
f64 rectSum(Rect r)   { return r.x + r.y + r.w + r.h; }
f64 vec2Dot(Vec2 a, Vec2 b) { return a.x*b.x + a.y*b.y; }
f64 v2dSum(V2d v)     { return v.a*2 + v.b; }
i64 bigSum(Big b)     { return b.a + b.b*10 + b.c*100; }
f64 mixed(i64 n, Color c, f64 f) { return n + c.r + c.g + f; }
i64 structThenInt(Color c, i64 n) { return c.r + c.g + n; }

Color makeColor(i64 base) {
    Color c; c.r = base; c.g = base+1; c.b = base+2; c.a = base+3; return c;
}
Vec2 makeVec2(f64 x, f64 y) { Vec2 v; v.x = x; v.y = y; return v; }
I2   makeI2(i64 a, i64 b)   { I2 v; v.a = a; v.b = b; return v; }

bool integerArgs() {
    i64 tests = 3, correct = 0;
    Color c; c.r=1; c.g=2; c.b=3; c.a=4;
    if (colorSum(c) == 4321) { correct++; }
    I2 v; v.a=5; v.b=7;
    if (i2Sum(v) == 57) { correct++; }
    Big b; b.a=1; b.b=2; b.c=3;
    if (bigSum(b) == 321) { correct++; }
    return correct == tests;
}

bool hfaArgs() {
    i64 tests = 3, correct = 0;
    Rect r; r.x=1.0; r.y=2.0; r.w=3.0; r.h=4.0;
    if (rectSum(r) == 10.0) { correct++; }
    Vec2 a; a.x=2.0; a.y=3.0;
    Vec2 b; b.x=4.0; b.y=5.0;
    if (vec2Dot(a, b) == 23.0) { correct++; }        /* 8 + 15 */
    V2d d; d.a=2.5; d.b=1.0;
    if (v2dSum(d) == 6.0) { correct++; }
    return correct == tests;
}

bool mixedArgs() {
    i64 tests = 2, correct = 0;
    Color c; c.r=1; c.g=2; c.b=3; c.a=4;
    if (mixed(100, c, 5.0) == 108.0) { correct++; }
    if (structThenInt(c, 100) == 103) { correct++; }
    return correct == tests;
}

bool structReturns() {
    i64 tests = 3, correct = 0;
    Color c = makeColor(10);
    if (c.r==10 && c.g==11 && c.b==12 && c.a==13) { correct++; }
    Vec2 v = makeVec2(2.5, 7.5);
    if (v.x==2.5 && v.y==7.5) { correct++; }
    I2 i = makeI2(100, 200);
    if (i.a==100 && i.b==200) { correct++; }
    return correct == tests;
}

i32 main() {
    printf("Test - struct by value (C ABI):\n");
    i64 tests = 4, correct = 0;
    if (integerArgs())   { correct++; }
    if (hfaArgs())       { correct++; }
    if (mixedArgs())     { correct++; }
    if (structReturns()) { correct++; }
    test.printResult(correct,tests);
    printf("====\n");
    return 0;
}
