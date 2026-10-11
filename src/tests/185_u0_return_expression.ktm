import set, test;

/* `return <expr>;` in a void function dropped the expression: the
 * function has no return slot, and the value was only evaluated to be
 * stored there, so `void Wrap() { return Inc(); }` never called Inc.
 * It is now evaluated for its side effects and the value discarded.
 * IntSetAdd in the stdlib is written this way. */

i64 g = 0;

void inc() { g++; }
void wrap() { return inc(); }

i64 inc2() { g++; return g; }
void wrap2() { return inc2(); }

void early(i64 n) {
    if (n > 0) { return inc(); }
    g += 100;
}

void assign() { return g = 50; }
void postInc() { return g++; }

inline void inlWrap() { return inc(); }
inline void inlWrap2() { return inc2(); }

i64 twice(i64 x) { g += x; return x * 2; }
void nested() { return twice(twice(1)); }

i32 main() {
    i64 correct = 0, tests = 0;
    set.IntSet *s;

    printf("Test - return with an expression in a void function: ");

    wrap();
    tests++; if (g == 1) { correct++; }
    wrap2();
    tests++; if (g == 2) { correct++; }
    early(1);
    tests++; if (g == 3) { correct++; }
    early(0);
    tests++; if (g == 103) { correct++; }
    assign();
    tests++; if (g == 50) { correct++; }
    postInc();
    tests++; if (g == 51) { correct++; }
    g = 0;
    nested();
    tests++; if (g == 3) { correct++; }

    g = 0;
    inlWrap();
    tests++; if (g == 1) { correct++; }
    inlWrap2();
    tests++; if (g == 2) { correct++; }

    /* IntSetAdd is `void ... { return RBTreeInsert(...); }` */
    s = set.intNew();
    s.add(5);
    s.add(7);
    s.add(5);
    tests++; if (s.has(5) && s.has(7) && !s.has(6)) { correct++; }
    tests++; if (s.size() == 2) { correct++; }
    s.release();

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
