import test;

/* An inlined call binds a parameter the body only reads to its argument's
 * value. When the body declared a local from that parameter
 * (`i64 *p = (i64 *)w;`), the declaration redirected the instruction that
 * computed the argument (a load of `slot[0]`) to write the local instead,
 * so the parameter itself was never set: `f(slot[0]) - slot[0]` gave 173
 * compiled and garbage in the JIT instead of 5. A plain local as the
 * argument was right, as it is no temporary. */

struct Box {
    i64 a;
    i64 b;
};

static i64 viaPtr(i64 w) {
    i64 *p = (i64 *)w;
    i64 v = *p;
    return w + v;
}

static i64 copyFirst(i64 w) {
    i64 c = w;
    return c + w;
}

static i64 useBefore(i64 w) {
    i64 before = w * 2;
    i64 c = w;
    i64 after = w + c;
    return before + after;
}

static i64 twoParams(i64 x, i64 y) {
    i64 a = x;
    i64 b = y;
    return a * 100 + b + x * 10 + y;
}

static f64 floats(f64 w) {
    f64 c = w;
    return c + w;
}

static i64 ptrParam(i64 *p) {
    i64 *q = p;
    return *q + *p;
}

static i64 seven() {
    return 7;
}

i32 main() {
    printf("Test - inline array argument: ");
    i64 correct = 0, tests = 0;

    i64 x = 5;
    i64 slot[3];
    slot[0] = (i64)&x;
    slot[1] = 11;
    slot[2] = 13;
    i64 a = (i64)&x;

    tests++;
    if (viaPtr(a) - a == 5 && viaPtr(slot[0]) - slot[0] == 5) { correct++; }

    i64 i = 1;
    tests++;
    if (copyFirst(slot[i]) == 22 && copyFirst(slot[i + 1]) == 26) { correct++; }

    i64 *ptr = &slot[1];
    tests++;
    if (copyFirst(*ptr) == 22 && useBefore(*ptr) == 44) { correct++; }

    Box box;
    box.a = 3;
    box.b = 4;
    Box *bp = &box;
    tests++;
    if (copyFirst(box.b) == 8 && copyFirst(bp->a) == 6 && useBefore(bp->b) == 16) {
        correct++;
    }

    tests++;
    if (copyFirst(seven()) == 14 && useBefore(seven() + 1) == 32) { correct++; }

    tests++;
    if (twoParams(slot[1], slot[2]) == 1100 + 13 + 110 + 13) { correct++; }

    f64 fs[2];
    fs[0] = 1.5;
    fs[1] = 2.25;
    tests++;
    if (floats(fs[1]) == 4.5) { correct++; }

    i64 *ps[1];
    ps[0] = &x;
    tests++;
    if (ptrParam(ps[0]) == 10) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
