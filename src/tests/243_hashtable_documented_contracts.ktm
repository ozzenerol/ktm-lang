import cstr, map, mem, test;

/* What the library docs and std.ktmh now promise about map.IntMap and map.StrMap:
 * the capacity rule (slots, a power of two, grows at 60% full, never
 * shrinks, not even on Clear), insertion-order iteration with deletes
 * allowed during it and a re-set key coming last, an update keeping the
 * stored key and freeing the key passed, and map.strToStr's format. */

i64 gKeyFrees = 0;

void freeKey(void *p) {
    gKeyFrees++;
    mem.free(p);
}

u8 *strFn(void *v) {
    return cstr.formatTo(null, "%d", v);
}

i32 main() {
    printf("Test - hash table documented contracts: ");
    i64 tests = 0;
    i64 correct = 0;

    /* Capacity: rounded up, grows when 60% of the slots are used */
    map.IntMap *im = map.intNew(1000);
    for (i64 i = 0; i < 614; i++) { im.set(i, (void *)i); }
    i64 before = im.capacity;
    im.set(614, null);
    tests++;
    if (before == 1024 && im.capacity == 2048) { correct++; }
    else { printf("Failed - growth at 60%%: %d then %d\n", before, im.capacity); }

    /* Never shrinks */
    for (i64 i = 0; i <= 614; i++) { im.delete(i); }
    i64 afterDelete = im.capacity;
    im.clear();
    tests++;
    if (afterDelete == 2048 && im.capacity == 2048 && im.size == 0) { correct++; }
    else { printf("Failed - shrank: %d %d\n", afterDelete, im.capacity); }
    im.release();

    /* Deleting every entry while iterating visits each once */
    map.StrMap *m = map.strNew();
    u8 *names[5] = {"a", "b", "c", "d", "e"};
    for (i64 i = 0; i < 5; i++) { m.set(names[i], (void *)i); }
    i64 idx = 0, visited = 0;
    map.StrMapNode *node;
    while (m.iter(&idx, &node)) {
        if ((i64)node.value != visited) { visited = -100; }
        visited++;
        m.delete(node.key);
    }
    tests++;
    if (visited == 5 && m.size == 0) { correct++; }
    else { printf("Failed - delete while iterating: %d\n", visited); }

    /* A deleted key set again comes last */
    for (i64 i = 0; i < 5; i++) { m.set(names[i], (void *)i); }
    m.delete("b");
    m.set("b", (void *)9);
    u8 *keys = m.keysToStr();
    u8 *all = m.toStr(&strFn);
    tests++;
    if (!cstr.cmp(keys, "{a, c, d, e, b}") &&
            !cstr.cmp(all, "{[a] => 0, [c] => 2, [d] => 3, [e] => 4, [b] => 9}")) {
        correct++;
            } else { printf("Failed - order: %s %s\n", keys, all); }
    mem.free(keys);
    mem.free(all);
    m.release();

    /* An update keeps the stored key and frees the one passed */
    m = map.strNew();
    m.setFreeKey(&freeKey);
    u8 *first = cstr.dup("k");
    m.set(first, (void *)1);
    m.set(cstr.dup("k"), (void *)2);
    u8 *stored;
    idx = 0;
    m.keyIter(&idx, &stored);
    tests++;
    if (gKeyFrees == 1 && stored == first && (i64)m.get("k") == 2) {
        correct++;
    } else { printf("Failed - update\n"); }
    m.release();
    tests++;
    if (gKeyFrees == 2) { correct++; }
    else { printf("Failed - release\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
