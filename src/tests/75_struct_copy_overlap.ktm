#include "testhelper.ktm"

/* A <=16-byte class returned by value comes back in registers; the callee
 * copies the returned value into a struct-sized return slot that the
 * epilogue then loads. The frame layout used to give that slot only the
 * 8 bytes of a hidden out-pointer, so `return local;` was a memcpy between
 * overlapping stack ranges (undefined behaviour, flagged by ASan as
 * memcpy-param-overlap under -jit). Only >16-byte returns use an
 * out-pointer. */

struct Pair { i64 a, b; };          /* 16B -> 2 INTEGER eightbytes */
struct Mix  { i64 n; f64 f; };      /* 16B -> INTEGER + SSE */
struct Tri  { i32 a, b, c; };       /* 12B -> 2 INTEGER eightbytes */

Pair makePair(i64 a, i64 b) { Pair p; p.a = a; p.b = b; return p; }
Mix  makeMix(i64 n, f64 f)  { Mix m; m.n = n; m.f = f; return m; }
Tri  makeTri(i32 n)         { Tri t; t.a = n; t.b = n * 2; t.c = n * 3; return t; }

/* Several locals and a return from each branch. */
Pair pickPair(i64 n) {
    Pair lo, hi;
    i64 sum = 0;
    for (i64 i = 1; i <= n; i++) { sum += i; }
    lo.a = sum; lo.b = -sum;
    hi.a = sum * 100; hi.b = n;
    if (n > 5) { return hi; }
    return lo;
}

bool returnLocal() {
    Pair p = makePair(100, 200);
    Mix m = makeMix(42, 9.5);
    Tri t = makeTri(7);
    return p.a == 100 && p.b == 200 && m.n == 42 && m.f == 9.5 &&
                 t.a == 7 && t.b == 14 && t.c == 21;
}

bool returnFromBranches() {
    Pair lo = pickPair(4);           /* 1+2+3+4 */
    Pair hi = pickPair(10);          /* 1+..+10 */
    return lo.a == 10 && lo.b == -10 && hi.a == 5500 && hi.b == 10;
}

bool returnIntoExisting() {
    Pair p;
    p = makePair(-1, -2);
    p = makePair(p.b, p.a);          /* swap through a call */
    return p.a == -2 && p.b == -1 && makePair(3, 4).b == 4;
}

i32 main() {
    printf("Test - struct copies don't overlap: ");
    i64 tests = 3, correct = 0;
    if (returnLocal())        { correct++; }
    if (returnFromBranches()) { correct++; }
    if (returnIntoExisting()) { correct++; }
    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
