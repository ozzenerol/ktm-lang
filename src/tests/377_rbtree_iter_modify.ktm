import test;

import map, mem, set, str;

/* RBIterNew, IntSetIterNew and RBTreeForEach size their stack from the
 * tree's size when they start, so adding keys during the walk wrote past
 * it, and a delete left freed nodes on it. The tree now counts its
 * changes: once a new key is added or one is deleted, the iterator stops
 * (Next returns null, RBTreeForEach returns after that callback).
 * Replacing the value of a key that is there doesn't stop it. */

i64 cmp(void *a, void *b) {
    if ((i64)a < (i64)b) { return -1; }
    if ((i64)a > (i64)b) { return 1; }
    return 0;
}

i64 strKeyCmp(void *a, void *b) {
    return str.cmp(a, b);
}

map.RBTree *treeFn(i64 n) {
    map.RBTree *t = map.rbNew(null, null, &cmp);
    for (i64 i = 1; i <= n; i++) { t.set((void *)(i * 10), (void *)i); }
    return t;
}

/* every key of t, in order, and the count is `n` */
bool sorted(map.RBTree *t, i64 n) {
    map.RBIter *it = t.iterNew();
    map.RBNode *node;
    i64 count = 0, prev = I64_MIN;
    bool ok = true;
    while ((node = map.rbIterNext(it))) {
        if ((i64)node.key <= prev) { ok = false; }
        prev = (i64)node.key;
        count++;
    }
    map.rbIterRelease(it);
    return ok && count == n && t.size == n;
}

i64 gCalls;

void insertMany(void *tree, void *key, void *value) {
    gCalls++;
    for (i64 i = 0; i < 500; i++) { map.RBTree.set(tree, (void *)(1000 + i), null); }
}

void deleteAll(void *tree, void *key, void *value) {
    gCalls++;
    for (i64 i = 1; i <= 8; i++) { map.RBTree.delete(tree, (void *)(i * 10)); }
}

void replaceValue(void *tree, void *key, void *value) {
    gCalls++;
    map.RBTree.set(tree, key, (void *)((i64)value + 100));
}

i32 main() {
    printf("Test - RBTree and IntSet iterators after a change: ");
    i64 correct = 0, tests = 0, n, i;
    map.RBTree *t;
    map.RBIter *it;
    map.RBNode *node;
    set.IntSet *set;
    set.IntSetIter *sit;
    set.IntSetEntry *e;

    /* RBIter: inserting far more keys than the tree had stops the walk */
    t = treeFn(3);
    it = t.iterNew();
    n = 0;
    while ((node = map.rbIterNext(it))) {
        n++;
        for (i = 0; i < 500; i++) { t.set((void *)(1000 + i), null); }
    }
    tests++; if (n == 1) { correct++; }
    tests++; if (map.rbIterNext(it) == null) { correct++; }
    map.rbIterRelease(it);
    tests++; if (sorted(t, 503)) { correct++; }
    t.release();

    /* RBIter: deleting every other node (stacked ones included) stops it */
    t = treeFn(8);
    it = t.iterNew();
    n = 0;
    while ((node = map.rbIterNext(it))) {
        n++;
        for (i = 1; i <= 8; i++) {
            if (i != 4) { t.delete((void *)(i * 10)); }
        }
    }
    tests++; if (n == 1) { correct++; }
    map.rbIterRelease(it);
    tests++; if (sorted(t, 1) && t.has((void *)40)) { correct++; }
    t.release();

    /* RBIter: deleting the current key, whose key the tree frees */
    t = map.rbNew(&mem.free, null, &strKeyCmp);
    t.set(str.dup("a"), null);
    t.set(str.dup("b"), null);
    t.set(str.dup("c"), null);
    it = t.iterNew();
    n = 0;
    while ((node = map.rbIterNext(it))) {
        n++;
        t.delete(node.key);
    }
    tests++; if (n == 1 && t.size == 2) { correct++; }
    map.rbIterRelease(it);
    t.release();

    /* RBIter: replacing values doesn't stop it */
    t = treeFn(20);
    it = t.iterNew();
    n = 0;
    while ((node = map.rbIterNext(it))) {
        n++;
        t.set(node.key, (void *)((i64)node.value * 2));
    }
    tests++; if (n == 20 && (i64)t.get((void *)70) == 14) { correct++; }
    map.rbIterRelease(it);
    t.release();

    /* RBTreeForEach stops after the callback that changed the tree */
    t = treeFn(3);
    gCalls = 0;
    t.forEach(t, &insertMany);
    tests++; if (gCalls == 1 && sorted(t, 503)) { correct++; }
    t.release();

    t = treeFn(8);
    gCalls = 0;
    t.forEach(t, &deleteAll);
    tests++; if (gCalls == 1 && t.size == 0) { correct++; }
    t.release();

    t = treeFn(8);
    gCalls = 0;
    t.forEach(t, &replaceValue);
    tests++; if (gCalls == 8 && (i64)t.get((void *)30) == 103) { correct++; }
    t.release();

    /* IntSet: adding many keys while iterating stops the iterator */
    set = set.intNew();
    for (i = 1; i <= 3; i++) { set.add(i); }
    sit = set.iterNew();
    n = 0;
    while ((e = set.intIterNext(sit))) {
        n++;
        for (i = 100; i < 600; i++) { set.add(i); }
    }
    tests++; if (n == 1 && set.size() == 503) { correct++; }
    set.intIterRelease(sit);

    /* adding a key that is there changes nothing, so it doesn't stop it */
    sit = set.iterNew();
    n = 0;
    while ((e = set.intIterNext(sit))) {
        n++;
        set.add(e.key);
    }
    tests++; if (n == 503) { correct++; }
    set.intIterRelease(sit);

    /* IntSet: deleting the current key stops it too */
    sit = set.iterNew();
    n = 0;
    while ((e = set.intIterNext(sit))) {
        n++;
        set.delete(e.key);
    }
    tests++; if (n == 1 && set.size() == 502 && !set.has(1)) { correct++; }
    set.intIterRelease(sit);
    set.release();

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
