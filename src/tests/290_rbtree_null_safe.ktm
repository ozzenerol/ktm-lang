import map, test;

/* RBTreeGet, RBTreeHas, RBTreeInsert, RBTreeDelete, RBTreeForEach and
 * RBIterNew dereferenced a null tree; only RBTreeRelease checked. Reads
 * now give null/false, writes do nothing and RBIterNew(null) gives an
 * iterator that yields nothing. */

i64 gCalls;

void countCall(void *user_data, void *key, void *value) { gCalls++; }

i64 intCmp(void *a, void *b) {
    if ((i64)a < (i64)b) { return -1; }
    if ((i64)a > (i64)b) { return 1; }
    return 0;
}

bool nullReads() {
    return map.RBTree.get(null, (void *)1) == null &&
                 map.RBTree.get(null, null) == null &&
                 map.RBTree.has(null, (void *)1) == false &&
                 map.RBTree.has(null, null) == false;
}

bool nullWrites() {
    map.RBTree.set(null, (void *)1, (void *)10);
    map.RBTree.set(null, null, null);
    map.RBTree.delete(null, (void *)1);
    map.RBTree.delete(null, null);
    map.RBTree.release(null);
    return true;
}

bool nullForEach() {
    gCalls = 0;
    map.RBTree.forEach(null, null, &countCall);
    return gCalls == 0;
}

bool nullIter() {
    map.RBIter *it = map.RBTree.iterNew(null);
    if (!it) { return false; }
    i64 n = 0;
    while (map.rbIterNext(it)) { n++; }
    bool ok = n == 0 && map.rbIterNext(it) == null;
    map.rbIterRelease(it);
    map.rbIterRelease(null);
    return ok && map.rbIterNext(null) == null;
}

bool realTreeUnchanged() {
    map.RBTree *t = map.rbNew(null, null, &intCmp);
    for (i64 i = 1; i <= 30; ++i) { t.set((void *)i, (void *)(i * 2)); }
    t.delete((void *)7);
    bool ok = t.size == 29 && (i64)t.get((void *)8) == 16 &&
                        t.has((void *)30) && !t.has((void *)7);
    gCalls = 0;
    t.forEach(null, &countCall);
    ok = ok && gCalls == 29;
    map.RBIter *it = t.iterNew();
    map.RBNode *node;
    i64 n = 0, prev = 0;
    while ((node = map.rbIterNext(it))) {
        if ((i64)node.key <= prev) { ok = false; }
        prev = (i64)node.key;
        n++;
    }
    map.rbIterRelease(it);
    t.release();
    return ok && n == 29;
}

bool emptyTreeIter() {
    map.RBTree *t = map.rbNew(null, null, &intCmp);
    map.RBIter *it = t.iterNew();
    bool ok = map.rbIterNext(it) == null && t.get((void *)1) == null;
    map.rbIterRelease(it);
    t.release();
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - RBTree functions accept a null tree: ");
    tests++; if (nullReads()) { correct++; }
    else { printf("RBTreeGet/RBTreeHas on null\n"); }
    tests++; if (nullWrites()) { correct++; }
    tests++; if (nullForEach()) { correct++; }
    else { printf("RBTreeForEach(null) called the callback\n"); }
    tests++; if (nullIter()) { correct++; }
    else { printf("RBIterNew(null) did not give an empty iterator\n"); }
    tests++; if (realTreeUnchanged()) { correct++; }
    else { printf("a real tree misbehaved\n"); }
    tests++; if (emptyTreeIter()) { correct++; }
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
