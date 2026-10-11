import test;

/* A function name without `(` is a call with no arguments, wherever it
 * appears in an expression. Only `Function;`, `Function,` and `Function)`
 * were calls; `F == 3` or `F + 1` left a bare function in the tree and
 * crashed ktm ("Expr Unhandled AST kind: AST_FUNC"). `&F` stays the
 * function's address. */

struct Pt {
    i64 x, y;
};

i64 gCalls = 0;
Pt gPt;

i64 F() { gCalls++; return 3; }
i64 G(i64 a = 4, i64 b = 5) { return a + b; }
f64 half() { return 0.5; }
Pt *getPt() { return &gPt; }
u8 *nameFn() { return "abc"; }
i64 twice(i64 x) { return x * 2; }

i32 main() {
    printf("Test - call without parentheses: ");
    i64 correct = 0, tests = 0;

    tests++; if (F() == 3) { correct++; }
    tests++; i64 x = F() + 1; if (x == 4) { correct++; }
    tests++; if (G() * 2 == 18) { correct++; }
    tests++; if (-F() == -3) { correct++; }
    tests++; if (!F() == 0) { correct++; }
    tests++; if (F() * F() == 9) { correct++; }
    tests++; f64 h = half() * 4; if (h == 2.0) { correct++; }
    tests++;
    gPt.x = 7; gPt.y = 8;
    if (getPt().x == 7 && getPt().y == 8) { correct++; }
    tests++; if (nameFn()[1] == 'b') { correct++; }
    tests++; if (twice(F()) == 6) { correct++; }
    tests++;
    i64 i = 0, n = 0;
    while (i < F()) { i++; n++; }
    if (n == 3) { correct++; }
    tests++; x = 0; x += F(); if (x == 3) { correct++; }

    /* The address and function pointers are unaffected */
    tests++;
    fn() i64 fp = &F;
    if (fp() == 3 && fp == &F) { correct++; }
    tests++;
    fn(i64 v) i64 tw = &twice;
    if (tw(F()) == 6) { correct++; }

    tests++; if (gCalls == 14) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
