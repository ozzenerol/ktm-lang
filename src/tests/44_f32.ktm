#include "testhelper.ktm"

/* f32 arithmetic is only exact for values representable in single
 * precision (sums of powers of two within the 24-bit mantissa), so
 * unlike 26_f64.ktm these comparisons stick to halves/quarters/eighths
 * where the result is exact in both f32 and f64. An f32 compared
 * against an f64 literal is widened first, so the two sides match
 * exactly when the value is representable. */

bool f32Addition() {
    i64 tests = 6, correct = 0;
    f32 f1 = 0.5 + 0.25;
    if (f1 == 0.75) { correct++; }

    f32 f2 = 1.5 + 2.5;
    if (f2 == 4.0) { correct++; }

    f32 f3 = 100.0 + 0.5;
    if (f3 == 100.5) { correct++; }

    f32 f4 = -3.25 + 1.25;
    if (f4 == -2.0) { correct++; }

    f32 f5 = 0.125 + 0.125;
    if (f5 == 0.25) { correct++; }

    f32 f6 = 16777216.0 + 0.0;   /* 2^24, largest exact integer run */
    if (f6 == 16777216.0) { correct++; }

    return correct == tests;
}

bool f32Subtraction() {
    i64 tests = 6, correct = 0;
    f32 f1 = 0.75 - 0.25;
    if (f1 == 0.5) { correct++; }

    f32 f2 = 4.0 - 2.5;
    if (f2 == 1.5) { correct++; }

    f32 f3 = 100.5 - 0.5;
    if (f3 == 100.0) { correct++; }

    f32 f4 = (-2.0) - (1.25);
    if (f4 == -3.25) { correct++; }

    f32 f5 = 0.25 - 0.125;
    if (f5 == 0.125) { correct++; }

    f32 f6 = 10.0 - 10.0;
    if (f6 == 0.0) { correct++; }
    return correct == tests;
}

bool f32Division() {
    i64 tests = 6, correct = 0;
    f32 f1 = 1.0 / 4.0;
    if (f1 == 0.25) { correct++; }

    f32 f2 = 10.0 / 2.5;
    if (f2 == 4.0) { correct++; }

    f32 f3 = -6.0 / 2.0;
    if (f3 == -3.0) { correct++; }

    f32 f4 = 7.0 / 2.0;
    if (f4 == 3.5) { correct++; }

    f32 f5 = 0.25 / 0.25;
    if (f5 == 1.0) { correct++; }

    f32 f6 = 100.0 / 8.0;
    if (f6 == 12.5) { correct++; }
    return correct == tests;
}

bool f32Multiplication() {
    i64 tests = 6, correct = 0;
    f32 f1 = 0.5 * 0.5;
    if (f1 == 0.25) { correct++; }

    f32 f2 = 2.5 * 4.0;
    if (f2 == 10.0) { correct++; }

    f32 f3 = -3.0 * 2.0;
    if (f3 == -6.0) { correct++; }

    f32 f4 = 1.25 * 8.0;
    if (f4 == 10.0) { correct++; }

    f32 f5 = 0.125 * 0.125;
    if (f5 == 0.015625) { correct++; }

    f32 f6 = 9.0 * 0.0;
    if (f6 == 0.0) { correct++; }
    return correct == tests;
}

bool f32Operators() {
    i64 tests = 9, correct = 0;
    /* Use f32 variables so the relational ops genuinely exercise the
     * single-precision compare path, not an f64 literal fold. */
    f32 a = 1.5, b = 89.125, c = 1.75, d = 420.5;

    if (a * 8.0 > 10.0) { correct++; }   /* 1.5 * 8 = 12 */
    if (b == 89.125) { correct++; }
    if (d != b) { correct++; }
    if (a < b) { correct++; }
    if (a <= a) { correct++; }
    if (c > a) { correct++; }
    if (a == a) { correct++; }
    if (c >= a) { correct++; }
    if (a >= a) { correct++; }
    return tests == correct;
}

bool f32Conversions() {
    i64 tests = 6, correct = 0;

    /* int -> f32 */
    i64 n = 7;
    f32 a = n;
    if (a == 7.0) { correct++; }

    /* f32 -> int (truncates toward zero) */
    f32 b = 3.75;
    i64 i = b;
    if (i == 3) { correct++; }

    /* f32 -> f64 (widening keeps the value) */
    f32 c = 1.5;
    f64 d = c;
    if (d == 1.5) { correct++; }

    /* f64 -> f32 (narrowing, value is representable) */
    f64 e = 2.25;
    f32 f = e;
    if (f == 2.25) { correct++; }

    /* mixed f32 + f64 promotes to f64 */
    f32 g = 1.5;
    f64 h = g + 2.5;
    if (h == 4.0) { correct++; }

    /* negative -> int truncation */
    f32 k = -2.75;
    i64 m = k;
    if (m == -2) { correct++; }

    return correct == tests;
}

bool f32Precision() {
    i64 tests = 3, correct = 0;

    /* 2^24 + 1 is not representable in single precision and rounds back
     * to 2^24 - the defining difference between f32 and f64. */
    f32 a = 16777217.0;
    if (a == 16777216.0) { correct++; }

    /* The same value held in an f64 keeps its exact magnitude. */
    f64 b = 16777217.0;
    if (b != 16777216.0) { correct++; }

    /* Round-tripping a representable value through f32 is lossless. */
    f32 c = 0.5;
    f64 d = c;
    f32 e = d;
    if (e == 0.5) { correct++; }

    return correct == tests;
}

struct F32Vec { f32 x; f32 y; f32 z; };
struct F32Mix { u8  tag; f32 f; f64 d; };

/* Aggregate initialiser items are bare f64 literals; they must be coerced
 * to each field's real type and stored at the field's offset (an f32 field
 * gets a 4-byte single, not a truncated 8-byte double). */
bool f32StructInit() {
    i64 tests = 5, correct = 0;
    F32Vec v = {1.0, 2.5, 3.0};
    if (v.x == 1.0) { correct++; }
    if (v.y == 2.5) { correct++; }
    if (v.z == 3.0) { correct++; }
    F32Mix m = {7, 0.25, 8.5};
    if (m.tag == 7 && m.f == 0.25) { correct++; }
    if (m.d == 8.5) { correct++; }
    return correct == tests;
}

i32 main() {
    printf("Test - f32 (single precision) arithmetic:\n");
    i64 tests = 8, correct = 0;
    if (f32Addition()) { correct++; }
    if (f32Subtraction()) { correct++; }
    if (f32Division()) { correct++; }
    if (f32Multiplication()) { correct++; }
    if (f32Operators()) { correct++; }
    if (f32Conversions()) { correct++; }
    if (f32Precision()) { correct++; }
    if (f32StructInit()) { correct++; }
    PrintResult(correct,tests);
    printf("====\n");
    return 0;
}
