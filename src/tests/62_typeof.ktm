import cstr, os, test;

struct TypeofVec2 {
    f64 x;
    f64 y;
};

i64 correct = 0;
i64 tests = 8;

f64 half(f64 v) { return v / 2.0; }

void runMain() {
    i64 i = 5;
    u8 *s = "hi";
    TypeofVec2 v;

    if (!cstr.cmp(typeof(1+1), "i64")) { correct++; }
    if (!cstr.cmp(typeof(1.5), "f64")) { correct++; }
    if (!cstr.cmp(typeof(i), "i64")) { correct++; }
    if (!cstr.cmp(typeof(&i), "i64 *")) { correct++; }
    if (!cstr.cmp(typeof(s), "u8 *")) { correct++; }
    if (!cstr.cmp(typeof(v), "TypeofVec2")) { correct++; }
    if (!cstr.cmp(typeof(u8*), "u8 *")) { correct++; }
    /* The operand is type-checked, not evaluated. */
    if (!cstr.cmp(typeof(half(4.0)), "f64")) { correct++; }
}

void main() {
    printf("Test - typeof: ");
    runMain();
    test.printResult(correct, tests);
    printf("===\n");
    os.exit(0);
}
