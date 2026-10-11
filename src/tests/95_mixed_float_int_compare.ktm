#include "testhelper.ktm"

/* Comparing a float with an integer truncated the float to an integer
 * (`f64 b = 2.5; b == 2` was 1). Like C, the integer operand must be
 * converted to floating point and the comparison done on floats.
 * Every check compares the materialised value as an integer, so the
 * test never relies on a mixed comparison being a branch. */

i64 gCorrect = 0;

void expect(i64 got, i64 want) {
    if (got == want) { gCorrect++; }
}

f64 u64ToF64(u64 u) { f64 r = u; return r; }
f32 u64ToF32(u64 u) { f32 r = u; return r; }

i64 eqTwo(f64 x) { return x == 2; }
bool lessInt(f64 x, i64 n) { return x < n; }

i32 main() {
    i64 tests = 85;

    printf("Test - mixed float/integer comparison: ");

    f64 b = 2.5, m = -2.5, w = 2.0, z = 0.0;
    f64 nan = z / z;
    f32 f = 2.5;
    i64 two = 2, three = 3;
    i32 i32_ = 2;
    u8 u8_ = 2;
    u64 u64_ = 2;
    i64 v;

    /* The original report. */
    i64 r = b == 2;
    expect(r, 0);

    /* Every operator, integer literal on either side. */
    v = b == 2; expect(v, 0);  v = b != 2; expect(v, 1);
    v = b < 3;  expect(v, 1);  v = b <= 2; expect(v, 0);
    v = b > 2;  expect(v, 1);  v = b >= 3; expect(v, 0);
    v = 2 == b; expect(v, 0);  v = 2 != b; expect(v, 1);
    v = 2 < b;  expect(v, 1);  v = 3 <= b; expect(v, 0);
    v = 2 > b;  expect(v, 0);  v = 2 >= b; expect(v, 0);

    /* Integer variables of several widths, both orders. */
    v = b == two;   expect(v, 0);  v = b > two;    expect(v, 1);
    v = b < three;  expect(v, 1);  v = two < b;    expect(v, 1);
    v = three <= b; expect(v, 0);  v = two != b;   expect(v, 1);
    v = b == i32_;   expect(v, 0);  v = b > i32_;    expect(v, 1);
    v = i32_ >= b;   expect(v, 0);  v = i32_ < b;    expect(v, 1);
    v = b == u8_;    expect(v, 0);  v = b > u8_;     expect(v, 1);
    v = u8_ >= b;    expect(v, 0);  v = u8_ < b;     expect(v, 1);
    v = b == u64_;   expect(v, 0);  v = b > u64_;    expect(v, 1);
    v = u64_ >= b;   expect(v, 0);  v = u64_ < b;    expect(v, 1);

    /* Exact matches still compare equal. */
    v = w == 2;    expect(v, 1);  v = w == two;  expect(v, 1);
    v = w <= two;  expect(v, 1);  v = two >= w;  expect(v, 1);
    v = w == u8_;   expect(v, 1);  v = w != i32_;  expect(v, 0);

    /* Negative values: truncation toward zero gave -2 == -2. */
    v = m == -2; expect(v, 0);  v = m < -2;  expect(v, 1);
    v = -2 > m;  expect(v, 1);  v = m >= -2; expect(v, 0);

    /* f32 operand. */
    v = f == 2;    expect(v, 0);  v = f > 2;     expect(v, 1);
    v = f <= two;  expect(v, 0);  v = 2 < f;     expect(v, 1);
    v = f == i32_;  expect(v, 0);  v = u8_ >= f;   expect(v, 0);

    /* NaN against an integer: only != is true. */
    v = nan == 0; expect(v, 0);  v = nan != 0; expect(v, 1);
    v = nan < 1;  expect(v, 0);  v = nan >= 1; expect(v, 0);
    v = 0 == nan; expect(v, 0);  v = 0 != nan; expect(v, 1);

    /* Branch form behaves like the value form. */
    v = 0; if (b == 2) { v = 1; }    expect(v, 0);
    v = 0; if (b != 2) { v = 1; }    expect(v, 1);
    v = 0; if (b > two) { v = 1; }   expect(v, 1);
    v = 0; if (2 >= b) { v = 1; }    expect(v, 0);
    v = 0; if (nan == 0) { v = 1; }  expect(v, 0);
    v = 0; while (b <= 2) { v = 1; break; } expect(v, 0);
    v = 0; for (; b > 2; ) { v = 7; break; } expect(v, 7);

    /* Function returns. */
    expect(eqTwo(2.5), 0);
    expect(eqTwo(2.0), 1);
    expect(lessInt(2.5, 3), 1);
    expect(lessInt(2.5, 2), 0);

    /* Mixed arithmetic already promotes to float. */
    f64 s = b + 2, p = 2 * b, q = b / 2;
    i64 si = s * 10, pi = p * 10, qi = q * 100;
    expect(si, 45);
    expect(pi, 50);
    expect(qi, 125);

    /* u64 -> float uses an unsigned conversion: values with the top bit
     * set used to come out negative on x86_64. */
    f64 two64 = 65536.0 * 65536.0 * 65536.0 * 65536.0;
    f64 two63 = two64 / 2, two53 = two64 / 2048;
    u64 u0 = 0, u1 = 1, u53 = 1 << 53, u63 = 1 << 63;
    u64 u63b = (1 << 63) + (1 << 11), umax = 0xFFFFFFFFFFFFFFFF;
    f64 x;
    v = u64ToF64(u0) == 0.0;      expect(v, 1);
    v = u64ToF64(u1) == 1.0;      expect(v, 1);
    v = u64ToF64(u53) == two53;   expect(v, 1);
    v = u64ToF64(u63) == two63;   expect(v, 1);
    v = u64ToF64(u63b) == two63 + 2048.0; expect(v, 1);
    v = u64ToF64(umax) == two64;  expect(v, 1);
    x = umax;                     v = x == two64; expect(v, 1);
    x = umax * 1.0;               v = x == two64; expect(v, 1);
    x = (f64)umax;                v = x == two64; expect(v, 1);
    x = (f64)u63;                 v = x == two63; expect(v, 1);
    x = (u64)0xFFFFFFFFFFFFFFFF;  v = x == two64; expect(v, 1);
    v = umax > 1.0;  expect(v, 1);  v = u63 > 0.0;  expect(v, 1);
    v = 1.0 < u63;   expect(v, 1);  v = umax == two64; expect(v, 1);
    v = u64ToF32(umax) == two64;  expect(v, 1);
    v = u64ToF32(u63) == two63;   expect(v, 1);
    v = u64ToF32(u1) == 1.0;      expect(v, 1);

    PrintResult(gCorrect, tests);
    printf("====\n");
    return 0;
}
