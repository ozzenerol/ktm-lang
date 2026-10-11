import test, vec;

/* IntVecNew(0)/FloatVecNew(0) never grew (capacity * 2 of 0) and every
 * push wrote past the block; PtrVecNew ignored its size and always made
 * room for 32. A size below 1 (0 or negative) now starts with room for
 * one entry, and growing from a tiny capacity goes to at least 8. */

bool intVecFrom(i64 size, i64 n) {
    vec.IntVec *v = vec.intNew(size);
    if (v.capacity() < 1) { return false; }
    for (i64 i = 0; i < n; ++i) { v.push(i * 3); }
    bool ok = v.size == n && v.capacity() > n;
    for (i64 i = 0; i < n; ++i) { if (v.get(i) != i * 3) { ok = false; } }
    v.release();
    return ok;
}

bool floatVecFrom(i64 size, i64 n) {
    vec.FloatVec *v = vec.floatNew(size);
    for (i64 i = 0; i < n; ++i) { v.push(i * 0.5); }
    bool ok = v.size == n;
    for (i64 i = 0; i < n; ++i) { if (v.get(i) != i * 0.5) { ok = false; } }
    v.release();
    return ok;
}

bool ptrVecFrom(i64 size, i64 n) {
    vec.PtrVec *v = vec.ptrNew(size);
    for (i64 i = 0; i < n; ++i) { v.push((void *)(i + 1)); }
    bool ok = v.size == n;
    for (i64 i = 0; i < n; ++i) { if ((i64)v.get(i) != i + 1) { ok = false; } }
    v.release();
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - vector zero capacity: ");

    i64 sizes[5] = {0, -1, -1000, 1, 2};
    bool ok = true;
    for (i64 i = 0; i < 5; ++i) {
        if (!intVecFrom(sizes[i], 1000)) { ok = false; printf("IntVec size %d\n", sizes[i]); }
    }
    tests++; if (ok) { correct++; }

    ok = true;
    for (i64 i = 0; i < 5; ++i) {
        if (!floatVecFrom(sizes[i], 1000)) { ok = false; printf("FloatVec size %d\n", sizes[i]); }
    }
    tests++; if (ok) { correct++; }

    ok = true;
    for (i64 i = 0; i < 5; ++i) {
        if (!ptrVecFrom(sizes[i], 1000)) { ok = false; printf("PtrVec size %d\n", sizes[i]); }
    }
    tests++; if (ok) { correct++; }

    /* The requested size is the initial capacity, for all three */
    vec.IntVec *iv = vec.intNew(100);
    vec.FloatVec *fv = vec.floatNew(100);
    vec.PtrVec *pv = vec.ptrNew(100);
    vec.PtrVec *pv0 = vec.ptrNew(0);
    vec.PtrVec *pvd = vec.ptrNew();
    tests++;
    if (iv.capacity() == 100 && fv.capacity() == 100 &&
            pv.capacity() == 100 && pv0.capacity() == 1 &&
            pvd.capacity() == 32) { correct++; }
    else { printf("initial capacity: %d %d %d %d %d\n", iv.capacity(),
             fv.capacity(), pv.capacity(), pv0.capacity(),
             pvd.capacity());
    }
    iv.release();
    fv.release();
    pv.release();
    pv0.release();
    pvd.release();

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
