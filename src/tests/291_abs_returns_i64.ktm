import cstr, math, test;

/* Abs returned a u64, so everything done with its result was unsigned:
 * `Abs(x) > -1` was false and `f64 f = Abs(-3) - 5` was 1.8e19. It now
 * returns an i64; Abs(I64_MIN) stays I64_MIN, like C's labs. */

i64 gNegOne = -1;

i32 main() {
    printf("Test - Abs returns i64: ");
    i64 correct = 0, tests = 0;
    i64 x = -5, y = 12, zero = 0, mn = I64_MIN, mx = I64_MAX;
    f64 f;
    i64 i;

    tests++; if (math.abs(x) > -1) { correct++; }
    tests++; if (math.abs(y) > gNegOne) { correct++; }
    tests++; if (math.abs(zero) > -1) { correct++; }
    tests++; if (!(math.abs(x) < -1)) { correct++; }
    tests++; if (math.abs(x) >= gNegOne) { correct++; }

    f = math.abs(-3) - 5;
    tests++; if (f == -2.0) { correct++; }
    f = math.abs(x) - 10;
    tests++; if (f == -5.0) { correct++; }
    tests++; if (math.abs(-3) - 5 < 0) { correct++; }
    i = math.abs(-7) / -2;
    tests++; if (i == -3) { correct++; }
    tests++; if (math.abs(-7) % -4 == 3) { correct++; }
    tests++; if (-math.abs(-9) < 0) { correct++; }
    tests++; if (math.abs(-9) * -1 == -9) { correct++; }
    tests++; if ((math.abs(-16) - 32) >> 2 == -4) { correct++; }
    f = math.abs(-4);
    tests++; if (f == 4.0) { correct++; }

    tests++; if (math.abs(x) == 5) { correct++; }
    tests++; if (math.abs(y) == 12) { correct++; }
    tests++; if (math.abs(zero) == 0) { correct++; }
    tests++; if (math.abs(mx) == I64_MAX) { correct++; }
    tests++; if (math.abs(-mx) == I64_MAX) { correct++; }
    tests++; if (math.abs(mn + 1) == I64_MAX) { correct++; }
    tests++; if (math.abs(mn) == I64_MIN) { correct++; }
    tests++; if (math.abs(mn) < 0) { correct++; }
    f = math.abs(mn);
    tests++; if (f < 0.0) { correct++; }

    u8 buf[32];
    cstr.formatTo(buf, "%d", math.abs(-42) - 50);
    tests++; if (!cstr.cmp(buf, "-8")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
