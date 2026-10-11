import test;

import math;

/* Pow(x, I64_MIN) returned 1.0: negating the exponent overflowed back
 * to I64_MIN, which is not > 0, so the loop never ran. */

bool near(f64 got, f64 want) {
    f64 d = got - want;
    if (d < 0) { d = -d; }
    if (want < 0) { want = -want; }
    return d <= want * 1e-12;
}

i32 main() {
    printf("Test - Pow with I64_MIN: ");
    i64 correct = 0, tests = 0;
    f64 inf = 1.0 / 0.0;

    tests++; if (math.power(2, I64_MIN) == 0.0) { correct++; }
    tests++; if (math.power(0.5, I64_MIN) == inf) { correct++; }
    tests++; if (math.power(0.0, I64_MIN) == inf) { correct++; }
    tests++; if (math.power(-0.5, I64_MIN) == inf) { correct++; }  /* even exponent */
    tests++; if (math.power(1.0, I64_MIN) == 1.0) { correct++; }
    tests++; if (math.power(-1.0, I64_MIN) == 1.0) { correct++; }

    /* The exponent is an f64: I64_MAX and I64_MIN + 1 round to the even
     * +-2^63, so their sign follows pow(-x, 2^63). */
    tests++; if (math.power(2, I64_MAX) == inf) { correct++; }
    tests++; if (math.power(-2, I64_MAX) == inf) { correct++; }
    tests++; if (math.power(-1.0, I64_MAX) == 1.0) { correct++; }
    tests++; if (math.power(0.5, I64_MAX) == 0.0) { correct++; }
    tests++; if (math.power(-1.0, I64_MIN + 1) == 1.0) { correct++; }
    tests++; if (math.power(-1.0, 4503599627370497) == -1.0) { correct++; }  /* 2^52+1, odd */

    /* Ordinary exponents. */
    tests++; if (math.power(2, 10) == 1024.0) { correct++; }
    tests++; if (math.power(2, 0) == 1.0) { correct++; }
    tests++; if (math.power(0.0, 0) == 1.0) { correct++; }
    tests++; if (math.power(-2, 3) == -8.0) { correct++; }
    tests++; if (math.power(-2, 4) == 16.0) { correct++; }
    tests++; if (math.power(2, -2) == 0.25) { correct++; }
    tests++; if (math.power(-2, -3) == -0.125) { correct++; }
    tests++; if (near(math.power(1.5, 7), 17.0859375)) { correct++; }
    tests++; if (near(math.power(10, -3), 0.001)) { correct++; }
    tests++; if (math.power(0.0, 3) == 0.0) { correct++; }
    tests++; if (math.power(0.0, -1) == inf) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
