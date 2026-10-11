import cstr, map, mem, test;

/* map.strSet stored the caller's key pointer, and setting a key that
 * already existed replaced the stored pointer with the new one. Counting
 * words from a reused buffer then left the map's keys pointing at that
 * buffer, so every key turned into the last word read. Since v0.3.0 the map
 * copies every new key (a u8[]) and keeps that copy on updates; with a value
 * destructor registered the replaced value is freed exactly once. */

i64 gValueFrees = 0;

void countingFreeValue(void *value) {
    gValueFrees++;
    mem.free(value);
}

u8 *dup(u8 *s) {
    i64 len = cstr.len(s);
    u8 *d = mem.alloc(len + 1);
    mem.copy(d, s, len + 1);
    return d;
}

/* Allocate test strings in this file so -Memsafe tracks every one. */
u8 *fmtFn(u8 *fmt, i64 i) {
    u8 tmp[64];
    cstr.formatTo(tmp, fmt, i);
    return dup(tmp);
}

i64 *box(i64 v) {
    i64 *b = mem.alloc(sizeof(i64));
    *b = v;
    return b;
}

i32 main() {
    i64 correct = 0, tests = 0, i, bad;

    printf("Test - StrMapSet keeps the stored key on update: ");

    u8 *words[8];
    words[0] = "the";
    words[1] = "cat";
    words[2] = "sat";
    words[3] = "on";
    words[4] = "the";
    words[5] = "mat";
    words[6] = "the";
    words[7] = "cat";

    /* Word count from a reused buffer: the buffer itself is passed every
     * time. */
    map.StrMap *counts = map.strNew();
    u8 buf[32];
    for (i = 0; i < 8; i++) {
        cstr.copy(buf, words[i]);
        u8[] w = cstr.view(buf);
        counts.set(w, (void *)((i64)counts.get(w) + 1));
    }
    cstr.copy(buf, "zzzzzz");

    tests++;
    if (counts.size == 5) { correct++; }
    tests++;
    if ((i64)counts.get("the") == 3 && (i64)counts.get("cat") == 2 &&
            (i64)counts.get("sat") == 1 && (i64)counts.get("on") == 1 &&
            (i64)counts.get("mat") == 1) { correct++; }
    tests++;
    if (!counts.has("zzzzzz")) { correct++; }

    /* Every stored key is still its own copy, in insertion order. */
    u8 *order[5];
    order[0] = "the";
    order[1] = "cat";
    order[2] = "sat";
    order[3] = "on";
    order[4] = "mat";
    i64 idx = 0, n = 0;
    map.StrMapNode *node;
    bad = 0;
    while (counts.iter(&idx, &node) && n < 10) {
        if (n >= 5 || node.key.ptr == buf || cstr.cmp(node.key.ptr, order[n])) { bad++; }
        n++;
    }
    tests++;
    if (n == 5 && bad == 0) { correct++; }
    counts.release();

    /* Many updates through one buffer, across resizes. */
    map.StrMap *big = map.strNew(8);
    for (i = 0; i < 2000; i++) {
        big.set(cstr.view(cstr.formatTo(buf, "word-%d", i)), (void *)0);
    }
    for (i64 round = 1; round <= 3; round++) {
        for (i = 0; i < 2000; i++) {
            big.set(cstr.view(cstr.formatTo(buf, "word-%d", i)), (void *)round);
        }
    }
    cstr.copy(buf, "gone");
    bad = 0;
    for (i = 0; i < 2000; i++) {
        u8 *k = fmtFn("word-%d", i);
        if ((i64)big.get(cstr.view(k)) != 3) { bad++; }
        mem.free(k);
    }
    idx = 0;
    n = 0;
    while (big.iter(&idx, &node) && n <= 2000) {
        if (node.key.ptr == buf) { bad++; }
        n++;
    }
    tests++;
    if (bad == 0 && n == 2000 && big.size == 2000) { correct++; }
    big.release();

    /* The map owns its values with a destructor: an update frees the value
     * it replaces, never the one stored again. */
    map.StrMap *own = map.strNew();
    own.setFreeValue(&countingFreeValue);
    for (i = 0; i < 100; i++) {
        u8 *k = fmtFn("k%d", i);
        own.set(cstr.view(k), box(i));
        mem.free(k);
    }
    tests++;
    if (gValueFrees == 0 && own.size == 100) { correct++; }

    for (i = 0; i < 100; i++) {
        u8 *k = fmtFn("k%d", i);
        own.set(cstr.view(k), box(i + 1000));
        mem.free(k);
    }
    tests++;
    if (gValueFrees == 100 && own.size == 100) { correct++; }

    /* Setting the stored key and the stored value again frees nothing. */
    idx = 0;
    n = 0;
    while (own.iter(&idx, &node) && n < 200) {
        own.set(node.key, node.value);
        n++;
    }
    tests++;
    if (n == 100 && gValueFrees == 100) { correct++; }

    bad = 0;
    for (i = 0; i < 100; i++) {
        u8 *k = fmtFn("k%d", i);
        i64 *v = own.get(cstr.view(k));
        if (!v || *v != i + 1000) { bad++; }
        mem.free(k);
    }
    tests++;
    if (bad == 0) { correct++; }

    own.release();
    tests++;
    if (gValueFrees == 200) { correct++; }

    /* map.IntMap: replacing a value frees the old one once. */
    gValueFrees = 0;
    map.IntMap *im = map.intNew();
    im.setFreeValue(&countingFreeValue);
    for (i = 0; i < 300; i++) {
        im.set(i, box(i));
    }
    for (i = 0; i < 300; i++) {
        im.set(i, box(i * 2));
    }
    tests++;
    if (gValueFrees == 300 && im.size == 300) { correct++; }
    for (i = 0; i < 300; i++) {
        im.set(i, im.get(i));
    }
    bad = 0;
    for (i = 0; i < 300; i++) {
        i64 *v = im.get(i);
        if (!v || *v != i * 2) { bad++; }
    }
    tests++;
    if (bad == 0 && gValueFrees == 300) { correct++; }
    im.release();
    tests++;
    if (gValueFrees == 600) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
