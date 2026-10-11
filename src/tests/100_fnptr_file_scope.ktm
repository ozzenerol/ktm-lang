import cstr, test;

/* A function pointer declared at file scope - `i64 (*g)(i64 a, i64 b)
 * = &Add;` - failed with "Identifier expected", with or without
 * parameter names, initialiser or not. */

struct S {
    fn(i64 x) i64 f;
};

i64 add(i64 a, i64 b) { return a + b; }
i64 sub(i64 a, i64 b) { return a - b; }
i64 dbl(i64 x) { return x * 2; }
i64 apply(fn(i64 x) i64 f, i64 v) { return f(v); }
u8 *nameFn(i64 i) { if (i) { return "one"; } return "zero"; }
i64 gHits = 0;
void hit() { gHits++; }
i64 count(...args) { return args.len; }

fn(i64 a, i64 b) i64 g = &add;
fn(i64, i64) i64 h = &sub;
fn(i64 a, i64 b) i64 later;
fn(i64 a, i64 b = 10) i64 k = &add;
fn(i64 x) i64 p1 = &dbl; fn(i64 x) i64 p2;
fn(fn(i64 x) i64 f, i64 v) i64 ap = &apply;
fn(i64 i) u8 *nm = &nameFn;
fn() void cb = &hit;
fn(...args) i64 va = &count;
static fn(i64 x) i64 st = &dbl;
i64 after = 41;

i64 callG(i64 a, i64 b) { return g(a, b); }

i32 main() {
    i64 correct = 0, tests = 14;

    printf("Test - function pointer at file scope: ");

    if (g(2, 3) == 5) { correct++; }
    if (h(9, 4) == 5) { correct++; }
    later = &sub;
    if (later(10, 3) == 7) { correct++; }
    if (k(5) == 15 && k(5, 1) == 6) { correct++; }
    p2 = &dbl;
    if (p1(4) == 8 && p2(5) == 10) { correct++; }
    if (ap(&dbl, 21) == 42 && ap(p1, 3) == 6) { correct++; }
    if (!cstr.cmp(nm(1), "one") && !cstr.cmp(nm(0), "zero")) { correct++; }
    cb(); cb();
    if (gHits == 2) { correct++; }
    if (va(1, 2, 3) == 3) { correct++; }
    if (st(8) == 16 && after == 41) { correct++; }

    /* Reassigned, called from another function, passed as an argument. */
    g = &sub;
    if (callG(7, 2) == 5) { correct++; }
    if (apply(p1, 6) == 12) { correct++; }

    /* Local and class-field function pointers unchanged. */
    fn(i64 a, i64 b) i64 lp = &add;
    if (lp(1, 2) == 3) { correct++; }
    S s;
    s.f = &dbl;
    if (s.f(7) == 14) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
