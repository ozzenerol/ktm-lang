import test;

import str, vec;

/* IntVecPop/FloatVecPop/PtrVecPop wrote through `_ok` unconditionally, so
 * IntVecPop(v, null) crashed and the flag had to be passed. It now
 * defaults to null and is only written when given. The empty-vector
 * sentinels stay: -1, -1.0 and null. */

i32 main() {
    i64 correct = 0, tests = 0;
    bool ok;
    printf("Test - optional pop ok flag: ");

    vec.IntVec *iv = vec.intNew();
    iv.push(10);
    iv.push(20);
    tests++;
    if (iv.pop() == 20 && iv.pop(null) == 10 && iv.pop() == -1 &&
            iv.size == 0) { correct++; }
    ok = true;
    tests++; if (iv.pop(&ok) == -1 && !ok) { correct++; }
    iv.push(5);
    ok = false;
    tests++; if (iv.pop(&ok) == 5 && ok) { correct++; }
    iv.release();

    vec.FloatVec *fv = vec.floatNew();
    fv.push(2.5);
    tests++;
    if (fv.pop() == 2.5 && fv.pop() == -1.0 && fv.size == 0) { correct++; }
    ok = true;
    tests++; if (fv.pop(&ok) == -1.0 && !ok) { correct++; }
    fv.release();

    vec.PtrVec *pv = vec.ptrNew();
    pv.push("x");
    pv.push(null);
    ok = false;
    /* a stored null is told apart from empty only through `_ok` */
    tests++; if (pv.pop(&ok) == null && ok) { correct++; }
    tests++; if (!str.cmp(pv.pop(), "x") && pv.pop() == null) { correct++; }
    ok = true;
    tests++; if (pv.pop(&ok) == null && !ok && pv.size == 0) { correct++; }
    pv.release();

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
