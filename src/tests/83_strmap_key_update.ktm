import test;

import map, mem, str;

/* StrMapSet stores the caller's key pointer (by design), but setting a key
 * that already existed replaced the stored pointer with the new one. Counting
 * words from a reused buffer then left the map's keys pointing at that
 * buffer, so every key turned into the last word read. Updates now keep the
 * stored key; with destructors registered the duplicate key and the replaced
 * value are freed exactly once. */

i64 gKeyFrees = 0;
i64 gValueFrees = 0;

void countingFreeKey(void *key) {
    gKeyFrees++;
    mem.free(key);
}

void countingFreeValue(void *value) {
    gValueFrees++;
    mem.free(value);
}

u8 *dup(u8 *s) {
    i64 len = str.len(s);
    u8 *d = mem.alloc(len + 1);
    mem.copy(d, s, len + 1);
    return d;
}

/* Allocate test strings in this file so -Memsafe tracks every one. */
u8 *Fmt(u8 *fmt, i64 i) {
    u8 tmp[64];
    str.formatTo(tmp, fmt, i);
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

    /* Word count from a reused buffer: new words get a copy, updates pass
     * the buffer itself. */
    map.StrMap *counts = map.strNew();
    u8 buf[32];
    for (i = 0; i < 8; i++) {
        str.copy(buf, words[i]);
        if (counts.has(buf)) {
            counts.set(buf, (void *)((i64)counts.get(buf) + 1));
        } else {
            counts.set(dup(buf), (void *)1);
        }
    }
    str.copy(buf, "zzzzzz");

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
        if (n >= 5 || node.key == buf || str.cmp(node.key, order[n])) { bad++; }
        n++;
    }
    tests++;
    if (n == 5 && bad == 0) {
        correct++;
        /* Only free the keys if none of them is `buf`, which lives on the stack. */
        counts.setFreeKey(&mem.free);
    }
    counts.release();

    /* Many updates through one buffer, across resizes. */
    map.StrMap *big = map.strNew(8);
    for (i = 0; i < 2000; i++) {
        str.formatTo(buf, "word-%d", i);
        big.set(dup(buf), (void *)0);
    }
    for (i64 round = 1; round <= 3; round++) {
        for (i = 0; i < 2000; i++) {
            str.formatTo(buf, "word-%d", i);
            big.set(buf, (void *)round);
        }
    }
    str.copy(buf, "gone");
    bad = 0;
    for (i = 0; i < 2000; i++) {
        u8 *k = Fmt("word-%d", i);
        if ((i64)big.get(k) != 3) { bad++; }
        mem.free(k);
    }
    idx = 0;
    n = 0;
    while (big.iter(&idx, &node) && n <= 2000) {
        if (node.key == buf) { bad++; }
        n++;
    }
    tests++;
    if (bad == 0 && n == 2000 && big.size == 2000) {
        correct++;
        big.setFreeKey(&mem.free);
    }
    big.release();

    /* The map owns keys and values: an update frees the duplicate key that
     * was passed and the value it replaces, never the stored key. */
    map.StrMap *own = map.strNew();
    own.setFreeKey(&countingFreeKey);
    own.setFreeValue(&countingFreeValue);
    for (i = 0; i < 100; i++) {
        u8 *k = Fmt("k%d", i);
        own.set(dup(k), box(i));
        mem.free(k);
    }
    tests++;
    if (gKeyFrees == 0 && gValueFrees == 0 && own.size == 100) { correct++; }

    for (i = 0; i < 100; i++) {
        u8 *k = Fmt("k%d", i);
        own.set(dup(k), box(i + 1000));
        mem.free(k);
    }
    tests++;
    if (gKeyFrees == 100 && gValueFrees == 100 && own.size == 100) { correct++; }

    /* Setting the stored key and the stored value again frees nothing. */
    idx = 0;
    n = 0;
    while (own.iter(&idx, &node) && n < 200) {
        own.set(node.key, node.value);
        n++;
    }
    tests++;
    if (n == 100 && gKeyFrees == 100 && gValueFrees == 100) { correct++; }

    bad = 0;
    for (i = 0; i < 100; i++) {
        u8 *k = Fmt("k%d", i);
        i64 *v = own.get(k);
        if (!v || *v != i + 1000) { bad++; }
        mem.free(k);
    }
    tests++;
    if (bad == 0) { correct++; }

    own.release();
    tests++;
    if (gKeyFrees == 200 && gValueFrees == 200) { correct++; }

    /* IntMap: replacing a value frees the old one once. */
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
