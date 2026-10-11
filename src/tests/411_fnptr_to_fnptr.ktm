import test;

/* A pointer to a function pointer, `i64 (**pp)(i64)`, was rejected
 * ("Expected function pointer name got *"), and `&fp` on a local function
 * pointer panicked in the IR. Now it works for locals, globals, parameters,
 * struct fields and arrays, reading and writing through it. */

i64 twice(i64 x) { return x * 2; }
i64 thrice(i64 x) { return x * 3; }

fn(i64) i64 gFp = &twice;
(fn(i64) i64) *gPp = null;

struct Slot {
    (fn(i64) i64) *at;
    (fn(i64) i64) *at2;
};

/* Point *pp at Thrice; returns what it pointed at before, applied to v */
i64 swap((fn(i64) i64) *pp, i64 v) {
    fn(i64) i64 old = *pp;
    *pp = &thrice;
    return old(v);
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - pointers to function pointers: ");

    tests++;   /* a local, pointing at a local function pointer */
    fn(i64) i64 f = &twice;
    (fn(i64) i64) *pp = &f;
    fn(i64) i64 g = *pp;
    i64 before = g(5);
    *pp = &thrice;
    if (before == 10 && f(5) == 15) { correct++; }
    else { printf("\n  local\n"); }

    tests++;   /* a global */
    gPp = &gFp;
    fn(i64) i64 h = *gPp;
    i64 b2 = h(4);
    *gPp = &thrice;
    if (b2 == 8 && gFp(4) == 12) { correct++; }
    else { printf("\n  global\n"); }

    tests++;   /* a parameter */
    fn(i64) i64 k = &twice;
    if (swap(&k, 3) == 6 && k(3) == 9) { correct++; }
    else { printf("\n  parameter\n"); }

    tests++;   /* struct fields and an array of them */
    fn(i64) i64 m = &twice;
    Slot s;
    s.at = &gFp;
    s.at2 = &m;
    (fn(i64) i64) *arr[2];
    arr[0] = &gFp;
    arr[1] = &k;
    fn(i64) i64 a0 = *arr[0];
    fn(i64) i64 a1 = *arr[1];
    fn(i64) i64 fa = *s.at;
    fn(i64) i64 fb = *s.at2;
    if (fa(2) == 6 && fb(2) == 4 && a0(1) == 3 && a1(1) == 3) { correct++; }
    else { printf("\n  fields and arrays\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
