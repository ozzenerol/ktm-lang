import test;

import map, mem, set;

/* RBTreeRelease freed the nodes but not the tree, and never called the
 * key/value destructors. RBTreeDelete already called them; updating an
 * existing key now also frees the duplicate key and the replaced value,
 * like StrMapAdd. */

i64 gKeyFrees, gKeySum, gValFrees, gValSum;

void resetCounts() {
    gKeyFrees = 0; gKeySum = 0;
    gValFrees = 0; gValSum = 0;
}

void countKey(void *key)   { gKeyFrees++; gKeySum += (i64)key; }
void countValue(void *val) { gValFrees++; gValSum += (i64)val; }

i64 intCmp(void *a, void *b) {
    if ((i64)a < (i64)b) { return -1; }
    if ((i64)a > (i64)b) { return 1; }
    return 0;
}

/* Boxed keys: the tree owns them, the destructor frees them */
i64 boxCmp(void *a, void *b) { return intCmp((void *)(*(i64 *)a), (void *)(*(i64 *)b)); }
void freeBox(void *key) {
    gKeyFrees++;
    gKeySum += *(i64 *)key;
    mem.free(key);
}
i64 *box(i64 v) {
    i64 *p = mem.alloc(sizeof(i64));
    *p = v;
    return p;
}

bool releaseCallsDestructors() {
    resetCounts();
    map.RBTree *t = map.rbNew(&countKey, &countValue, &intCmp);
    i64 keySum = 0, valSum = 0;
    for (i64 i = 1; i <= 100; ++i) {
        t.set((void *)i, (void *)(i * 10));
        keySum += i;
        valSum += i * 10;
    }
    t.release();
    return gKeyFrees == 100 && gValFrees == 100 &&
                 gKeySum == keySum && gValSum == valSum;
}

bool deleteThenRelease()
{// Every entry is freed exactly once, by RBTreeDelete or RBTreeRelease
    resetCounts();
    map.RBTree *t = map.rbNew(&countKey, &countValue, &intCmp);
    i64 keySum = 0, valSum = 0;
    for (i64 i = 1; i <= 64; ++i) {
        t.set((void *)i, (void *)(i * 3));
        keySum += i;
        valSum += i * 3;
    }
    for (i64 i = 2; i <= 64; i += 3) {
        t.delete((void *)i);
    }
    t.delete((void *)1000); /* missing: frees nothing */
    bool ok = gKeyFrees == 21 && gValFrees == 21 && t.size == 43;
    t.release();
    return ok && gKeyFrees == 64 && gValFrees == 64 &&
                 gKeySum == keySum && gValSum == valSum;
}

bool updateFreesOldValue() {
    resetCounts();
    map.RBTree *t = map.rbNew(&freeBox, &countValue, &boxCmp);
    t.set(box(7), (void *)70);
    t.set(box(7), (void *)71);       /* duplicate key + old value freed */
    bool ok = gKeyFrees == 1 && gKeySum == 7 &&
                        gValFrees == 1 && gValSum == 70;
    i64 seven = 7;
    ok = ok && (i64)t.get(&seven) == 71;
    /* Setting the same value again frees nothing but the duplicate key */
    t.set(box(7), (void *)71);
    ok = ok && gKeyFrees == 2 && gValFrees == 1;
    t.set(box(8), (void *)80);
    t.release();
    return ok && gKeyFrees == 4 && gKeySum == 7 + 7 + 7 + 8 &&
                 gValFrees == 3 && gValSum == 70 + 71 + 80;
}

bool nullDestructors() {
    resetCounts();
    map.RBTree *t = map.rbNew(null, null, &intCmp);
    for (i64 i = 1; i <= 50; ++i) {
        t.set((void *)i, (void *)i);
    }
    t.set((void *)5, (void *)500);
    t.delete((void *)10);
    t.release();
    return gKeyFrees == 0 && gValFrees == 0;
}

bool emptyTree() {
    resetCounts();
    map.RBTree *t = map.rbNew(&countKey, &countValue, &intCmp);
    t.release();
    map.RBTree.release(null);
    return gKeyFrees == 0 && gValFrees == 0;
}

bool intSetStillReleases() {
    set.IntSet *s = set.intNew();
    for (i64 i = 0; i < 20; ++i) { s.add(i); }
    s.delete(3);
    bool ok = s.has(4) && !s.has(3);
    s.release();
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - RBTreeRelease frees entries and tree: ");
    tests++; if (releaseCallsDestructors()) { correct++; }
    else { printf("release did not call the destructors for every entry\n"); }
    tests++; if (deleteThenRelease()) { correct++; }
    else { printf("delete + release did not free every entry exactly once\n"); }
    tests++; if (updateFreesOldValue()) { correct++; }
    else { printf("updating a key did not free the duplicate key / old value\n"); }
    tests++; if (nullDestructors()) { correct++; }
    tests++; if (emptyTree()) { correct++; }
    tests++; if (intSetStillReleases()) { correct++; }
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
