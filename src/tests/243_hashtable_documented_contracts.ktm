import cstr, map, str, test;

/* What the library docs and std.ktmh now promise about map.IntMap and map.StrMap:
 * the capacity rule (slots, a power of two, grows at 60% full, never
 * shrinks, not even on Clear), insertion-order iteration with deletes
 * allowed during it and a re-set key coming last, an update keeping the
 * stored key (the map's own copy since v0.3.0), and map.strToStr's format. */

String strFn(void *v) {
    return str.format("%d", v);
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
    for (i64 i = 0; i < 5; i++) { m.set(cstr.view(names[i]), (void *)i); }
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
    for (i64 i = 0; i < 5; i++) { m.set(cstr.view(names[i]), (void *)i); }
    m.delete("b");
    m.set("b", (void *)9);
    String keys = m.keysToStr();
    String all = m.toStr(&strFn);
    tests++;
    if (keys.equals("{a, c, d, e, b}") &&
            all.equals("{[a] => 0, [c] => 2, [d] => 3, [e] => 4, [b] => 9}")) {
        correct++;
            } else { printf("Failed - order: %s %s\n", keys, all); }
    keys.free();
    all.free();
    m.release();

    /* The map stores its own copy of a key, NUL-terminated, and an update
     * keeps it */
    m = map.strNew();
    u8 buf[8];
    cstr.copy(buf, "k");
    m.set(cstr.view(buf), (void *)1);
    u8[] stored;
    idx = 0;
    m.keyIter(&idx, &stored);
    u8 *firstCopy = stored.ptr;
    buf[0] = 'x';                       /* the caller's buffer changes */
    m.set("k", (void *)2);
    idx = 0;
    m.keyIter(&idx, &stored);
    tests++;
    if (stored.ptr == firstCopy && stored.ptr != buf && stored.equals("k") &&
            stored.ptr[1] == 0 && (i64)m.get("k") == 2 && !m.has("x") &&
            (i64)m.size == 1) {
        correct++;
    } else { printf("Failed - key copy\n"); }
    m.release();

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
