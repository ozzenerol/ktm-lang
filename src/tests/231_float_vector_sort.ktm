import test, vec;

/* vec.floatSort did nothing: its comparator took two f64 parameters but
 * vec.qsort passes the entries as void * in integer registers, so it
 * compared whatever was in the float registers. */

i64 gSeed = 12345;
i64 nextRand() {
    gSeed = (gSeed * 6364136223846793005 + 1442695040888963407);
    return (gSeed >> 33) & 0x7FFFFFFF;
}

bool isSorted(vec.FloatVec *v) {
    for (i64 i = 1; i < v.size; ++i) {
        if (v.entries[i - 1] > v.entries[i]) { return false; }
    }
    return true;
}

/* Sort `n` values made by `kind` and check order and contents */
bool sortCase(i64 n, i64 kind) {
    vec.FloatVec *v = vec.floatNew();
    f64 sum = 0.0;
    for (i64 i = 0; i < n; ++i) {
        f64 x;
        switch (kind) {
            case 0: x = (f64)(nextRand() % 2001 - 1000) / 8.0; /* +/-, dups */
            case 1: x = (f64)(n - i) * -0.25; /* negative, sorted desc */
            case 2: x = 1.5; /* all equal */
            case 3: x = (f64)(i % 7) - 3.0 + (f64)(i % 3) / 4.0;
        }
        sum += x;
        v.push(x);
    }
    v.sort();
    f64 after = 0.0;
    for (i64 i = 0; i < v.size; ++i) { after += v.entries[i]; }
    bool ok = v.size == n && isSorted(v) && after == sum;
    v.release();
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - FloatVecSort: ");

    vec.FloatVec *v = vec.floatNew();
    v.push(3.5);
    v.push(-1.25);
    v.push(2.0);
    v.push(-7.0);
    v.push(0.0);
    v.push(2.0);
    v.sort();
    f64 want[6] = {-7.0, -1.25, 0.0, 2.0, 2.0, 3.5};
    bool ok = v.size == 6;
    for (i64 i = 0; i < 6 && ok; ++i) { if (v.entries[i] != want[i]) { ok = false; } }
    tests++; if (ok) { correct++; }
    else {
        for (i64 i = 0; i < v.size; ++i) { printf("%.2f ", v.entries[i]); }
        printf("\n");
    }
    v.release();

    /* empty and single-element vectors */
    vec.FloatVec *e = vec.floatNew();
    e.sort();
    e.push(4.0);
    e.sort();
    tests++; if (e.size == 1 && e.entries[0] == 4.0) { correct++; }
    e.release();

    ok = true;
    i64 sizes[4] = {2, 17, 500, 5000};
    for (i64 k = 0; k < 4; ++k) {
        for (i64 s = 0; s < 4; ++s) {
            if (!sortCase(sizes[s], k)) { ok = false; printf("kind %d n %d unsorted\n", k, sizes[s]); }
        }
    }
    tests++; if (ok) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
