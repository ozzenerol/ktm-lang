import test;

bool floatAddition() {
    i64 tests = 6, correct = 0;
    f64 f1 = .0000001 + .0000002;
    if (f1 == 0.0000003) { correct++; }


    f64 f2 = 12345678.9 + 0.1111111;
    if (f2 == 12345679.011111101) { correct++; }

    f64 f3 = -12345.6789 + 12345.6788;
    /* floats are really weird */
    if (f3 == -0.00010000000111176632) { correct++; }

    f64 f4 = 1.0000000000000001 + 1.0000000000000001;
    if (f4 == 2.0000000000000002) { correct++; }

    f64 f5 = 0.3333333333333333 + 0.6666666666666666;
    if (f5 == 1) { correct++; }

    f64 f6 = 9.876543210123456 + 0.000000000000001;
    if (f6 == 9.876543210123458) { correct++; }

    return correct == tests;
}

bool floatSubraction() {
    i64 tests = 6, correct = 0;
    f64 f1 = 0.0000002 - 0.0000001;
    if (f1 ==  0.0000001) { correct++; }

    f64 f2 = 12345678.9 - 0.1111111;
    if (f2 == 12345678.7888889) { correct++; }

    f64 f3 = (-12345.6789) - (12345.6788);
    if (f3 == -24691.3577) { correct++; }


    f64 f4 = 1.000000000000001 - 0.000000000000001;
    if (f4 == 1.0) { correct++; }

    f64 f5 = 0.3333333333333333 - 0.3333333333333333;
    if (f5 == 0) { correct++; }

    f64 f6 = 9.8765432101234567 - 0.8765432101234567;
    if (f6 == 9) { correct++; }
    return correct == tests;
}

bool floatDivision() {
    i64 tests = 8, correct = 0;

    f64 f1 = 0.0000003 / 0.0000001;
    if (f1 == 3) { correct++; }

    f64 f2 = 12345678.9 / 1000000;
    if (f2 == 12.345678900000001) { correct++; }

    f64 f3 = -12345.6789 / 1;
    if (f3 == -12345.6789) { correct++; }

    f64 f4 = 1.0000000000000001 / 1.0000000000000001;
    if (f4 == 1) { correct++; }

    f64 f5 = 0.3333333333333333 / 0.3333333333333333;
    if (f5 == 1) { correct++; }

    /* 23/9 is integer division (2); comparing it with a float no longer
     * truncates the float, so divide by a float to test float division. */
    if (23/9.0 == 2.5555555555555554){ correct++; }

    if (24/10 < 50) { correct++; }

    f64 f6 = 9.876543210123456 / 9.876543210123456;
    if (f6 == 1) { correct++; }
    return correct == tests;
}

bool floatMultiplication() {
    i64 tests = 6, correct = 0;
    f64 f1 = 0.0000001 * 10000000;
    if (f1 == 1) { correct++; }

    f64 f2 = 12345.6789 * 0.0001;
    if (f2 == 1.2345678900000001) { correct++; }

    f64 f3 = -12345.6789 * -1;
    if (f3 == 12345.6789) { correct++; }

    f64 f4 = 1.0000000000000001 * 1.0000000000000001;
    if (f4 == 1.0) { correct++; }

    f64 f5 = 0.3333333333333333 * 3;
    if (f5 == 1.0) { correct++; }

    f64 f6 = 9.876543210123456 * 1.234567890123456;
    if (f6 == 12.193263112635261) { correct++; }
    return correct == tests;
}

bool floatOperators() {
    i64 tests = 9, correct = 0;

    if (45.5*8  > 32.123) { correct++; }
    if (89.1231 == 89.1231) { correct++; }
    if (420.1   != 89.1231) { correct++; }
    if (1.43234 < 89.1231) { correct++; }
    if (1.43234 <= 1.43234) { correct++; }
    if (1.43234 <= 1.43234) { correct++; }
    if (1.43234 == 1.43234) { correct++; }
    if (1.43234 >= 1.43230) { correct++; }
    if (1.43234 >= 1.43234) { correct++; }
    return tests == correct;
}

f64 retD() { return 7.5; }
i64 retI() { return 9; }

/* A function's float return assigned to an int variable (and vice versa)
 * must go through a real conversion (fcvtzs / scvtf), not a raw spill of
 * the return register. Regression for the decl-initialiser fast path that
 * used to redirect the call's dst straight into the slot, skipping the
 * conversion. */
bool floatReturnConversions() {
    i64 tests = 5, correct = 0;
    i64 a = retD();          if (a == 7) { correct++; }     /* f64 call -> int   */
    f64 b = retI();          if (b == 9.0) { correct++; }    /* int call -> f64   */
    i64 c; c = retD();       if (c == 7) { correct++; }      /* assignment form   */
    i64 d = retD() + 2;      if (d == 9) { correct++; }      /* in arithmetic     */
    f64 e = retD();
    i64 f = e;               if (f == 7) { correct++; }      /* via intermediate  */
    return tests == correct;
}

i32 main() {
    printf("Test - Floating point arithmetic:\n");
    i64 tests = 6, correct = 0;
    if (floatAddition()) { correct++; }
    if (floatSubraction()) { correct++; }
    if (floatDivision()) { correct++; }
    if (floatMultiplication()) { correct++; }
    if (floatOperators()) { correct++; }
    if (floatReturnConversions()) { correct++; }
    test.printResult(correct,tests);
    printf("====\n");
    return 0;
}
