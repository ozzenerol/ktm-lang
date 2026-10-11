import mem, test, vec;

/* IntVecSort/PtrVecSort used a last-element-pivot Lomuto quicksort that
 * recursed on both sides: O(n^2) on sorted, reversed and all-equal input,
 * and a stack overflow around 1e5 elements. It now uses a median-of-three
 * pivot, a three-way partition and recurses only into the smaller part.
 * A watchdog alarm turns a pathological slowdown into a failure. */

extern "c" u32 alarm(u32 seconds);

i64 gSeed = 42;
i64 nextRand() {
    gSeed = (gSeed * 6364136223846793005 + 1442695040888963407);
    return (gSeed >> 33) & 0x7FFFFFFF;
}

i64 makeValue(i64 kind, i64 i, i64 n) {
    switch (kind) {
        case 0: return i;                         /* sorted */
        case 1: return n - i;                     /* reversed */
        case 2: return 7;                         /* all equal */
        case 3: return nextRand() % 1000000;      /* random */
        case 4: return nextRand() % 4;            /* few distinct */
        case 5: if (i < n / 2) { return i; } return n - i; /* organ pipe */
    }
    return 0;
}

u8 *gKinds[6] = {"sorted", "reversed", "equal", "random", "few", "pipe"};

bool sortInts(i64 kind, i64 n) {
    vec.IntVec *v = vec.intNew();
    i64 sum = 0, xsum = 0;
    for (i64 i = 0; i < n; ++i) {
        i64 x = makeValue(kind, i, n);
        sum += x;
        xsum += x * x;
        v.push(x);
    }
    v.sort();
    bool ok = v.size == n;
    i64 sum2 = 0, xsum2 = 0;
    for (i64 i = 0; i < v.size; ++i) {
        if (i > 0 && v.entries[i - 1] > v.entries[i]) { ok = false; }
        sum2 += v.entries[i];
        xsum2 += v.entries[i] * v.entries[i];
    }
    ok = ok && sum == sum2 && xsum == xsum2;
    if (!ok) { printf("IntVecSort %s n=%d wrong\n", gKinds[kind], n); }
    v.release();
    return ok;
}

struct Item {
    i64 key;
    i64 id;
};

i64 itemLess(Item *a, Item *b) { return a.key < b.key; }
i64 itemLessEq(Item *a, Item *b) { return a.key <= b.key; }
i64 itemCStyle(Item *a, Item *b) { return a.key - b.key; }

bool sortItems(i64 n, fn(void *a, void *b) i64 cmp, bool checkOrder) {
    vec.PtrVec *v = vec.ptrNew();
    Item *items = mem.alloc(sizeof(Item) * n);
    i64 idsum = 0;
    for (i64 i = 0; i < n; ++i) {
        items[i].key = nextRand() % 100;
        items[i].id = i;
        idsum += i;
        v.push(&items[i]);
    }
    v.sort(cmp);
    bool ok = v.size == n;
    i64 idsum2 = 0;
    for (i64 i = 0; i < v.size; ++i) {
        Item *it = v.entries[i];
        idsum2 += it.id;
        if (checkOrder && i > 0) {
            Item *prev = v.entries[i - 1];
            if (prev.key > it.key) { ok = false; }
        }
    }
    ok = ok && idsum == idsum2;
    v.release();
    mem.free(items);
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - vector sort worst case: ");
    alarm(300);

    /* small sizes around the insertion-sort cutoff */
    bool ok = true;
    for (i64 n = 0; n < 70; ++n) {
        for (i64 k = 0; k < 6; ++k) { if (!sortInts(k, n)) { ok = false; } }
    }
    tests++; if (ok) { correct++; }

    ok = true;
    for (i64 k = 0; k < 6; ++k) { if (!sortInts(k, 100000)) { ok = false; } }
    tests++; if (ok) { correct++; }

    ok = true;
    for (i64 k = 0; k < 6; ++k) { if (!sortInts(k, 1000000)) { ok = false; } }
    tests++; if (ok) { correct++; }

    tests++; if (sortItems(200000, (fn(void *, void *) i64)(&itemLess), true)) { correct++; }
    else { printf("PtrVecSort with duplicates wrong\n"); }

    /* Other comparator styles must still terminate and keep every item;
     * `<=` still sorts, a C-style -1/0/1 comparator does not (documented). */
    tests++; if (sortItems(50000, (fn(void *, void *) i64)(&itemLessEq), true)) { correct++; }
    else { printf("`<=` comparator wrong\n"); }
    tests++; if (sortItems(50000, (fn(void *, void *) i64)(&itemCStyle), false)) { correct++; }
    else { printf("C-style comparator lost items\n"); }

    alarm(0);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
