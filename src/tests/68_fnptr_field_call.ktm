#include "testhelper.ktm"

/* Calling a function pointer field through anything other than a plain
 * variable - `arr[i]->f(x)`, `GetS()->f(x)`, `p->inner->f(x)` - was
 * parsed as a postfix cast `expr(Type)` and failed to compile. */

struct Shape {
    u8 *name;
    fn(Shape *s) f64 area;
    f64 w;
};

struct S {
    fn(i64 x) i64 f;
    fn() i64 none;
    fn(i64 a, i64 b, i64 c) i64 add3;
    i64 v;
};

struct Outer {
    S *inner;
    S in2;
};

i64 gCalls = 0;

f64 squareArea(Shape *s) { return s.w * s.w; }
f64 halfArea(Shape *s) { return s.w / 2.0; }
i64 dbl(i64 x) { gCalls++; return x * 2; }
i64 seven() { return 7; }
i64 Add3(i64 a, i64 b, i64 c) { return a + b + c; }

S gS;
S *getS() { return &gS; }

i32 main() {
    i64 correct = 0, tests = 20, i;

    printf("Test - call a function pointer field through an expression: ");

    Shape sq, half;
    sq.name = "square";
    sq.area = &squareArea;
    sq.w = 3.0;
    half.name = "half";
    half.area = &halfArea;
    half.w = 5.0;

    Shape *shapes[2];
    shapes[0] = &sq;
    shapes[1] = &half;
    f64 total = 0.0;
    for (i = 0; i < 2; i++) {
        total += shapes[i].area(shapes[i]);
    }
    if (total == 11.5) { correct++; }

    S s;
    s.f = &dbl;
    s.none = &seven;
    s.add3 = &Add3;
    s.v = 9;
    gS = s;

    S *arr[2];
    arr[0] = &s;
    arr[1] = &s;
    S vals[2];
    vals[0] = s;
    vals[1] = s;

    if (arr[0].f(4) == 8) { correct++; }
    if (arr[1].f(4) + 1 == 9) { correct++; }
    if (vals[1].f(6) == 12) { correct++; }
    if (getS().f(7) == 14) { correct++; }
    if (arr[0].none() == 7) { correct++; }
    if (vals[0].none() == 7) { correct++; }
    if (arr[0].add3(1, 2, 3) == 6) { correct++; }
    if (vals[0].add3(arr[0].f(1), vals[1].none(), getS().f(2)) == 13) {
        correct++;
    }
    if (dbl(arr[0].f(10)) == 40) { correct++; }

    /* As a statement on its own. */
    gCalls = 0;
    arr[0].f(4);
    vals[1].f(4);
    getS().f(4);
    if (gCalls == 3) { correct++; }

    Outer o;
    o.inner = &s;
    o.in2 = s;
    Outer *op = &o;
    Outer outs[1];
    outs[0] = o;
    if (op.inner.f(11) == 22) { correct++; }
    if (op.in2.f(12) == 24) { correct++; }
    if (outs[0].inner.f(13) == 26) { correct++; }
    if (outs[0].in2.f(14) == 28) { correct++; }

    /* Plain function pointers, field calls and postfix casts unchanged. */
    fn(i64 a) i64 fp = &dbl;
    S *p = &s;
    if (fp(15) == 30 && p.f(16) == 32 && s.f(17) == 34) { correct++; }
    if ((f64)arr[0].v == 9.0) { correct++; }
    if ((i64)arr[0] == &s) { correct++; }
    if ((f64)arr[0].f(2) == 4.0) { correct++; }
    i64 a = 3, b = 4;
    a * b;
    if (a * b == 12) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
