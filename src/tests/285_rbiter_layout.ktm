import map, mem, test;

/* std.ktmh declared RBIter with an extra `node` field that set.ktm's
 * RBIter doesn't have, so user code saw a 32-byte class while
 * RBIterNew allocated 24 bytes. */

i64 cmp(void *a, void *b) { return (i64)a - (i64)b; }

i32 main() {
    printf("Test - RBIter layout: ");
    i64 correct = 0, tests = 0, i, prev, n, ok;
    map.RBTree *t = map.rbNew(null, null, &cmp);
    map.RBNode *node;

    map.RBIter *it = t.iterNew();
    tests++; if (mem.size(it) == sizeof(map.RBIter)) { correct++; }
    tests++; if (map.rbIterNext(it) == null) { correct++; }
    map.rbIterRelease(it);

    for (i = 0; i < 100; i++) { t.set((void *)((i * 37) % 101), (void *)i); }
    it = t.iterNew();
    tests++; if (mem.size(it) == sizeof(map.RBIter)) { correct++; }
    ok = true; n = 0; prev = -1;
    while ((node = map.rbIterNext(it)) != null) {
        if ((i64)node.key <= prev) { ok = false; }
        prev = (i64)node.key;
        n++;
    }
    tests++; if (ok && n == 100) { correct++; }
    /* the fields user code can see are the ones RBIterNew filled in */
    tests++; if (it.sp == 0 && it.stack != null) { correct++; }
    map.rbIterRelease(it);
    t.release();

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
