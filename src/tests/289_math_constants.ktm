#include "testhelper.ktm"

import libc, math;

/* M_PI, M_E and the other maths constants were defined in math.ktm but
 * not exported in std.ktmh, so programs got "M_PI has not been defined". */

/* within an ulp or two: libm results may differ in the last bit */
bool near(f64 a, f64 b) {
    return libc.fabs(a - b) <= libc.fabs(b) * 1e-15;
}

i32 main() {
    printf("Test - maths constants: ");
    i64 correct = 0, tests = 0;

    tests++; if (near(math.PI, libc.acos(-1.0))) { correct++; }
    tests++; if (math.PI == 3.141592653589793) { correct++; }
    tests++; if (math.E == 2.718281828459045) { correct++; }
    tests++; if (math.PI_2 == math.PI / 2) { correct++; }
    tests++; if (math.PI_4 == math.PI / 4) { correct++; }
    tests++; if (math.INV_PI == 1 / math.PI) { correct++; }
    tests++; if (math.TWO_INV_PI == 2 / math.PI) { correct++; }
    tests++; if (near(math.TWO_INV_SQRTPI, 2 / libc.sqrt(math.PI))) { correct++; }
    tests++; if (near(math.E, libc.exp(1.0))) { correct++; }
    tests++; if (near(math.LOG2E, libc.log2(math.E))) { correct++; }
    tests++; if (near(math.LOG10E, libc.log10(math.E))) { correct++; }
    tests++; if (near(math.LN2, libc.log(2.0))) { correct++; }
    tests++; if (near(math.LN10, libc.log(10.0))) { correct++; }
    tests++; if (math.SQRT2 == libc.sqrt(2.0)) { correct++; }
    tests++; if (math.SQRT1_2 == libc.sqrt(0.5)) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
