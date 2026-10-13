import test;

/* Passing *p (a struct by value) as a call argument tagged p's own value as
 * a by-value struct, so every other call that passed p in that function
 * copied the struct instead of passing the pointer, even when the *p call
 * never ran (v0.13.2, AOT and JIT). */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

struct Big { i64 x; i64 y; i64 z; };
struct Small { i32 a; i32 b; };

Big gBig;
Small gSmall;

Big *getBig() { return &gBig; }
i64 sumBig(Big b) { return b.x + b.y + b.z; }
i64 sumSmall(Small s) { return s.a + s.b; }
/* the defer keeps them from being inlined: a real call */
i64 gCalls = 0;
Big *sameBig(Big *b) {
    defer gCalls++;
    return b;
}
Small *sameSmall(Small *s) {
    defer gCalls++;
    return s;
}

/* the by-value call never runs */
bool notTaken(bool never) {
    Big *p = getBig();
    if (never) { sumBig(*p); }
    return sameBig(p) == &gBig;
}

/* it runs, before and after the pointer is passed */
bool taken() {
    Big *p = getBig();
    i64 before = sumBig(*p);
    Big *q = sameBig(p);
    i64 after = sumBig(*p);
    return q == &gBig && before == 6 && after == 6;
}

/* a struct small enough for registers */
bool small(bool never) {
    Small *p = &gSmall;
    if (never) { sumSmall(*p); }
    return sameSmall(p) == &gSmall && sumSmall(*p) == 7;
}

/* a pointer parameter */
bool param(Big *p) {
    i64 s = sumBig(*p);
    return sameBig(p) == &gBig && s == 6;
}

i32 main() {
    printf("Test - deref struct arg: ");
    gBig.x = 1; gBig.y = 2; gBig.z = 3;
    gSmall.a = 3; gSmall.b = 4;
    check(notTaken(false), "f(p) after an untaken f(*p) passes p");
    check(taken(), "f(*p), g(p), f(*p) all see the right values");
    check(small(false), "a register-sized struct: g(p) passes p");
    check(param(&gBig), "a pointer parameter: g(p) after f(*p) passes p");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
