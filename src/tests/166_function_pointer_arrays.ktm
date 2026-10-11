#include "testhelper.ktm"

/* Arrays of function pointers, `i64 (*fns[2])(i64 x);`, did not parse
 * ("unexpected character `[`"): the declarator only took a name between
 * `(*` and `)`. Locals, globals, class fields and parameters, with calls
 * through `fns[i](x)`. */

i64 dbl(i64 x) { return x * 2; }
i64 sq(i64 x) { return x * x; }
i64 add(i64 a, i64 b = 10) { return a + b; }
f64 half(f64 x) { return x / 2.0; }

fn(i64 x) i64 gFns[2];
fn(i64 x) i64 gInit[] = {&sq, &dbl};

struct Ops {
    i64 n;
    fn(i64 x) i64 fns[2];
    i64 last;
};

Ops gOps = {3, {&dbl, &sq}, 9};

i64 applyAll(fn(i64 x) i64 fs[], i64 n, i64 v) {
    i64 i, total = 0;
    for (i = 0; i < n; i++) { total += fs[i](v); }
    return total;
}

i32 main() {
    printf("Test - arrays of function pointers: ");
    i64 correct = 0, tests = 0, i;

    fn(i64 x) i64 fns[2];
    fns[0] = &dbl;
    fns[1] = &sq;
    tests++; if (fns[0](5) == 10 && fns[1](5) == 25) { correct++; }
    tests++; if (sizeof(fns) == 16) { correct++; }

    i64 sum = 0;
    for (i = 0; i < 2; i++) { sum += fns[i](i + 3); }
    tests++; if (sum == 6 + 16) { correct++; }

    gFns[0] = &sq;
    gFns[1] = &dbl;
    tests++; if (gFns[0](3) == 9 && gFns[1](3) == 6) { correct++; }
    tests++; if (gInit[0](4) == 16 && gInit[1](4) == 8 && sizeof(gInit) == 16) { correct++; }

    fn(i64 x) i64 init[] = {&dbl, &sq, &dbl};
    tests++; if (sizeof(init) == 24 && init[2](7) == 14 && init[1](3) == 9) { correct++; }

    Ops o;
    o.n = 4;
    o.fns[0] = &sq;
    o.fns[1] = &dbl;
    o.last = 1;
    tests++; if (o.fns[0](o.n) == 16 && o.fns[1](o.n) == 8 && o.last == 1) { correct++; }
    tests++; if (sizeof(Ops) == 32 && gOps.fns[1](gOps.n) == 9 && gOps.last == 9) { correct++; }
    Ops *op = &o;
    tests++; if (op.fns[1](6) == 12) { correct++; }
    Ops lo = {2, {&dbl, &dbl}, 5};
    tests++; if (lo.fns[1](lo.n) == 4 && lo.last == 5) { correct++; }

    tests++; if (applyAll(fns, 2, 4) == 8 + 16) { correct++; }

    fn(i64 x) i64 grid[2][3];
    grid[1][2] = &sq;
    tests++; if (grid[1][2](5) == 25 && sizeof(grid) == 48) { correct++; }

    fn(i64 a, i64 b = 10) i64 adds[1];
    adds[0] = &add;
    tests++; if (adds[0](1) == 11 && adds[0](1, 2) == 3) { correct++; }

    fn(f64 x) f64 fl[1];
    fl[0] = &half;
    tests++; if (fl[0](3.0) == 1.5) { correct++; }

    /* Plain function pointers and more declarators after the array */
    fn(i64 x) i64 one = &sq; fn(i64 x) i64 more[2];
    more[1] = one;
    tests++; if (more[1](3) == 9 && one(2) == 4) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
