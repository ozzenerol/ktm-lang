#include "testhelper.ktm"

/* Leaf functions mixing constants with register-homed parameters.
 *
 * Regression for a store-forwarding bug: a leaf function's param arrives
 * register-homed (x0/x1 on AArch64), and `irForwardStoreToReads` rewrote a
 * read of its spill slot into the arrive value even in the r2 operand
 * position - but codegen materialises r1 into scratch x0 FIRST, so
 * `i64 D(i64 x){ return 10/x; }` emitted `mov x0,#10` over the param and
 * computed 10/10 == 1 for every input (same for +,-,*,%). The fix refuses
 * forwarding a scratch-homed source into positions the consumer's own
 * operand loading clobbers (r2, STORE_DEREF/RMW_DEREF value, phi pairs).
 * Wrong in BOTH AOT and JIT; x86_64 was immune (arg regs don't overlap
 * scratch). */

i64 divConstLeft(i64 x)  { return 10 / x; }
i64 addConstLeft(i64 x)  { return 10 + x; }
i64 subConstLeft(i64 x)  { return 10 - x; }
i64 mulConstLeft(i64 x)  { return 10 * x; }
i64 remConstLeft(i64 x)  { return 17 % x; }
i64 divConstRight(i64 x) { return x / 3; }
i64 selfDiv(i64 x)       { return x / x; }
i64 selfAdd(i64 x)       { return x + x; }

i64 sub2(i64 a, i64 b)    { return b - a; }
i64 sub2Rev(i64 a, i64 b) { return a - b; }
i64 div2(i64 a, i64 b)    { return b / a; }
i64 mix3(i64 a, i64 b, i64 c) { return a + 10 * b - c; }

f64 fDivConstLeft(f64 x) { return 10.0 / x; }
f64 fSub2(f64 a, f64 b)  { return b - a; }

void storeThrough(i64 a, i64 x, i64 *p) { *p = x; }

bool constLeftOps() {
    return divConstLeft(2) == 5  && addConstLeft(7) == 17 &&
                 subConstLeft(4) == 6  && mulConstLeft(4) == 40 &&
                 remConstLeft(5) == 2;
}

bool paramOnBothSides() {
    return divConstRight(21) == 7 && selfDiv(9) == 1 && selfAdd(21) == 42;
}

bool twoAndThreeParams() {
    return sub2(3, 10) == 7 && sub2Rev(10, 3) == 7 && div2(5, 30) == 6 &&
                 mix3(1, 2, 3) == 18;
}

bool floatLeafParams() {
    return fDivConstLeft(4.0) == 2.5 && fSub2(1.5, 4.0) == 2.5;
}

bool pointerStoreParam() {
    i64 v = 0;
    storeThrough(1, 42, &v);
    return v == 42;
}

i32 main() {
    printf("Test - leaf fns mixing constants with register-homed params:\n");
    i64 tests = 5, correct = 0;
    if (constLeftOps())       { correct++; }
    if (paramOnBothSides())   { correct++; }
    if (twoAndThreeParams())  { correct++; }
    if (floatLeafParams())    { correct++; }
    if (pointerStoreParam())  { correct++; }
    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
