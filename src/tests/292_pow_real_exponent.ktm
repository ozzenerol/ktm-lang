import libc, math, test;

/* Pow took an i64 exponent, so a real one was silently truncated:
 * Pow(4.0, 0.5) was 1.0 and Pow(2.0, 2.9) was 4.0. The exponent is now
 * an f64: integral values in the i64 range keep the exact repeated
 * squaring, everything else (fractional, NaN, infinite, huge) is pow's. */

bool near(f64 got, f64 want) {
    f64 d = got - want;
    if (d < 0) { d = -d; }
    if (want < 0) { want = -want; }
    return d <= want * 1e-12;
}

bool isNan(f64 x) { return x != x; }

i32 main() {
    printf("Test - Pow with a real exponent: ");
    i64 correct = 0, tests = 0;
    f64 inf = 1.0 / 0.0, nn = 0.0 / 0.0, half = 0.5, ex = 1.5;
    i64 ib = 3, ie = 4;

    /* Fractional exponents. */
    tests++; if (math.power(4.0, 0.5) == 2.0) { correct++; }
    tests++; if (math.power(4.0, half) == 2.0) { correct++; }
    tests++; if (math.power(4.0, ex) == 8.0) { correct++; }
    tests++; if (math.power(2.0, 2.9) == libc.pow(2.0, 2.9)) { correct++; }
    tests++; if (near(math.power(2.0, 2.9), 7.464263932294459)) { correct++; }
    tests++; if (near(math.power(2.0, -0.5), 0.7071067811865476)) { correct++; }
    tests++; if (math.power(27.0, 1.0 / 3.0) == libc.pow(27.0, 1.0 / 3.0)) { correct++; }
    tests++; if (math.power(0.0, 0.5) == 0.0) { correct++; }
    tests++; if (math.power(0.0, -0.5) == inf) { correct++; }
    tests++; if (isNan(math.power(-8.0, 1.0 / 3.0))) { correct++; }
    tests++; if (isNan(math.power(-2.0, 0.5))) { correct++; }

    /* Integral exponents keep the repeated-squaring results. */
    tests++; if (math.power(2, 10) == 1024.0) { correct++; }
    tests++; if (math.power(ib, ie) == 81.0) { correct++; }
    tests++; if (math.power(2.0, 3.0) == 8.0) { correct++; }
    tests++; if (math.power(-2.0, 3.0) == -8.0) { correct++; }
    tests++; if (math.power(-2, 4) == 16.0) { correct++; }
    tests++; if (math.power(10, -2) == 0.1 * 0.1) { correct++; }
    tests++; if (math.power(2.0, -0.0) == 1.0) { correct++; }
    tests++; if (math.power(-0.0, -1) == -inf) { correct++; }
    tests++; if (math.power(-1.0, 4503599627370497.0) == -1.0) { correct++; }
    tests++; if (math.power(2, I64_MIN) == 0.0) { correct++; }
    tests++; if (math.power(0.5, I64_MIN) == inf) { correct++; }
    i64 r = math.power(3, 3);
    tests++; if (r == 27) { correct++; }

    /* NaN and infinite exponents behave like pow. */
    tests++; if (isNan(math.power(2.0, nn))) { correct++; }
    tests++; if (math.power(1.0, nn) == 1.0) { correct++; }
    tests++; if (math.power(nn, 0) == 1.0) { correct++; }
    tests++; if (math.power(nn, 0.0) == 1.0) { correct++; }
    tests++; if (isNan(math.power(nn, 2))) { correct++; }
    tests++; if (math.power(2.0, inf) == inf) { correct++; }
    tests++; if (math.power(0.5, inf) == 0.0) { correct++; }
    tests++; if (math.power(2.0, -inf) == 0.0) { correct++; }
    tests++; if (math.power(0.5, -inf) == inf) { correct++; }
    tests++; if (math.power(-1.0, inf) == 1.0) { correct++; }
    tests++; if (math.power(-1.0, -inf) == 1.0) { correct++; }

    /* Integral but outside the i64 range: pow. */
    tests++; if (math.power(-1.0, 1e300) == 1.0) { correct++; }
    tests++; if (math.power(1.5, 1e19) == inf) { correct++; }
    tests++; if (math.power(1.5, -1e19) == 0.0) { correct++; }
    tests++; if (math.power(-2.0, 9223372036854775808.0) == inf) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
