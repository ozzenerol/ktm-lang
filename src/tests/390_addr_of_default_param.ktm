import test;

import str;

/* Taking the address of a parameter that has a default value stopped the
 * compiler (AOT and JIT) with "&AST_DEFAULT_PARAM not supported in
 * slice": references to such a parameter are wrapped in a default-param
 * node, which the address-of lowering didn't look through. It now works
 * like any other parameter, read and written through the pointer, with
 * or without the default, called with or without parentheses. */

struct Pt {
    i64 x, y;
};

Pt gPt = {3, 4};

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

void setI64(i64 *p, i64 v) { *p = v; }

i64 rdBool(bool b = 1) { return ((u8 *)(&b))[0]; }
i64 rwI64(i64 x = 41) { i64 *p = &x; *p += 1; return x; }
i64 rwI32(i32 v = 7) { i32 *p = &v; *p = *p * 3; return v; }
i64 rwU8(u8 c = 'a') { u8 *p = &c; *p += 1; return c; }
f64 rwF64(f64 f = 1.5) { f64 *p = &f; *p *= 2; return f; }
f64 rwF32(f32 f = 0.5) { f32 *p = &f; *p += 1; return f; }
u8 *rwPtr(u8 *s = "dflt") { u8 **pp = &s; *pp += 1; return s; }
i64 rwClassPtr(Pt *c = &gPt) {
    Pt **pc = &c;
    i64 sum = (*pc).x + c.y;
    *pc = null;               /* the parameter itself changes */
    if (c != null) { return -1; }
    return sum;
}
i64 viaHelper(i64 x = 5) { setI64(&x, x * 10); return x; }
i64 two(i64 a = 1, i64 b = 2) {
    i64 *pa = &a, *pb = &b;
    *pa += 100;
    *pb += 200;
    return a * 1000 + b;
}

i32 main() {
    Pt q = {10, 20};
    printf("Test - address of a parameter with a default value: ");

    check(rdBool() == 1 && rdBool() == 1 && rdBool(0) == 0, "bool read");
    check(rwI64() == 42 && rwI64() == 42 && rwI64(9) == 10, "i64 read/write");
    check(rwI32() == 21 && rwI32() == 21 && rwI32(2) == 6, "i32 read/write");
    check(rwU8() == 'b' && rwU8() == 'b' && rwU8('x') == 'y', "u8 read/write");
    check(rwF64() == 3.0 && rwF64() == 3.0 && rwF64(0.25) == 0.5,
            "f64 read/write");
    check(rwF32() == 1.5 && rwF32() == 1.5 && rwF32(2.0) == 3.0,
            "f32 read/write");
    check(!str.cmp(rwPtr(), "flt") && !str.cmp(rwPtr(), "flt") &&
            !str.cmp(rwPtr("xyz"), "yz"), "pointer read/write");
    check(rwClassPtr() == 7 && rwClassPtr() == 7 && rwClassPtr(&q) == 30,
            "class pointer read/write");
    check(viaHelper() == 50 && viaHelper() == 50 && viaHelper(3) == 30,
            "&param passed to a function");
    check(two() == 101202 && two() == 101202 && two(5) == 105202 &&
            two(5, 6) == 105206, "two defaulted params");
    check(gPt.x == 3 && gPt.y == 4, "the default object is untouched");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
