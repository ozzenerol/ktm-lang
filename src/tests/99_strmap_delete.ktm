import map, mem, str, test;

/* map.strDelete wrote HT_DELETED into the insertion-order vector at the
 * *slot* index (a heap overflow once the slot was past the vector's end),
 * returned true for keys that were not there, and inserting after a delete
 * could reuse a deleted slot while the key still existed further along the
 * probe chain (duplicate keys). Deleted slots were never cleared, so enough
 * delete/insert cycles left no empty slot and a lookup for a missing key
 * never terminated. map.intDelete left the node visible to iteration and its
 * freed value was freed again by map.intRelease. Every loop here is bounded so
 * a regression fails instead of hanging. */

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

u8 *fmtFn(u8 *fmt, i64 i) {
    u8 tmp[64];
    str.formatTo(tmp, fmt, i);
    return dup(tmp);
}

i64 *box(i64 v) {
    i64 *b = mem.alloc(sizeof(i64));
    *b = v;
    return b;
}

u8 *intStr(void *v) {
    return str.formatTo(null, "%d", (i64)v);
}

i64 keyNum(u8 *key)
{/* "key-123" -> 123 */
    return str.toInt(key + 4);
}

i32 main() {
    i64 correct = 0, tests = 0, i, bad, n, idx;
    map.StrMapNode *node;
    map.IntMapNode *inode;
    u8 buf[64];

    printf("Test - StrMapDelete / IntMapDelete: ");

    /* Basic semantics on a small map. */
    map.StrMap *s = map.strNew();
    s.set("a", (void *)1);
    s.set("b", (void *)2);
    s.set("c", (void *)3);
    s.set("", (void *)4);
    tests++;
    if (s.delete("b") && !s.delete("b") &&
            !s.delete("zz") && s.size == 3) { correct++; }
    tests++;
    if (!s.has("b") && s.get("b") == null &&
            (i64)s.get("a") == 1 && (i64)s.get("c") == 3) { correct++; }
    /* The empty key must not match a deleted slot. */
    tests++;
    if (s.delete("") && !s.has("") && !s.delete("") &&
            s.size == 2) { correct++; }
    u8 *keys = s.keysToStr();
    tests++;
    if (!str.cmp(keys, "{a, c}")) { correct++; }
    mem.free(keys);
    keys = s.toStr(&intStr);
    tests++;
    if (!str.cmp(keys, "{[a] => 1, [c] => 3}")) { correct++; }
    mem.free(keys);
    s.set("b", (void *)5);
    keys = s.keysToStr();
    tests++;
    if (!str.cmp(keys, "{a, c, b}") && (i64)s.get("b") == 5 &&
            s.size == 3) { correct++; }
    mem.free(keys);
    s.release();

    /* 10,000 owned keys and values; delete the even half. */
    map.StrMap *big = map.strNew();
    big.setFreeKey(&countingFreeKey);
    big.setFreeValue(&countingFreeValue);
    for (i = 0; i < 10000; i++) {
        big.set(fmtFn("key-%d", i), box(i));
    }
    bad = 0;
    for (i = 0; i < 10000; i += 2) {
        str.formatTo(buf, "key-%d", i);
        if (!big.delete(buf)) { bad++; }
    }
    tests++;
    if (bad == 0 && big.size == 5000 && gKeyFrees == 5000 &&
            gValueFrees == 5000) { correct++; }

    /* Deleting again or deleting keys that never existed is false. */
    bad = 0;
    for (i = 0; i < 10000; i += 2) {
        str.formatTo(buf, "key-%d", i);
        if (big.delete(buf)) { bad++; }
        str.formatTo(buf, "missing-%d", i);
        if (big.delete(buf) || big.has(buf)) { bad++; }
    }
    tests++;
    if (bad == 0 && big.size == 5000 && gKeyFrees == 5000) { correct++; }

    bad = 0;
    for (i = 0; i < 10000; i++) {
        str.formatTo(buf, "key-%d", i);
        i64 *v = big.get(buf);
        if (i & 1) {
            if (!v || *v != i) { bad++; }
        } else if (v != null || big.has(buf)) {
            bad++;
        }
    }
    tests++;
    if (bad == 0) { correct++; }

    /* Iteration skips the deleted keys and keeps insertion order. */
    idx = 0;
    n = 0;
    bad = 0;
    while (big.iter(&idx, &node) && n <= 10000) {
        if (keyNum(node.key) != n * 2 + 1) { bad++; }
        n++;
    }
    tests++;
    if (bad == 0 && n == 5000) { correct++; }

    /* Updating surviving keys must not create duplicates. */
    for (i = 1; i < 10000; i += 2) {
        big.set(fmtFn("key-%d", i), box(i + 100000));
    }
    idx = 0;
    n = 0;
    while (big.iter(&idx, &node) && n <= 20000) {
        n++;
    }
    tests++;
    if (big.size == 5000 && n == 5000 && gKeyFrees == 10000 &&
            gValueFrees == 10000) { correct++; }

    /* Re-insert the deleted half: they come after the survivors. */
    for (i = 0; i < 10000; i += 2) {
        big.set(fmtFn("key-%d", i), box(i));
    }
    idx = 0;
    n = 0;
    bad = 0;
    while (big.iter(&idx, &node) && n <= 20000) {
        i64 want;
        if (n < 5000) { want = n * 2 + 1; }
        else { want = (n - 5000) * 2; }
        if (keyNum(node.key) != want) { bad++; }
        n++;
    }
    tests++;
    if (bad == 0 && n == 10000 && big.size == 10000) { correct++; }

    bad = 0;
    for (i = 0; i < 10000; i++) {
        str.formatTo(buf, "key-%d", i);
        i64 *v = big.get(buf);
        if (!v) { bad++; }
        else if ((i & 1) && *v != i + 100000) { bad++; }
        else if (!(i & 1) && *v != i) { bad++; }
    }
    tests++;
    if (bad == 0) { correct++; }

    /* Release frees every remaining key and value exactly once:
     * 20,000 keys and 20,000 values were handed to the map in total. */
    big.release();
    tests++;
    if (gKeyFrees == 20000 && gValueFrees == 20000) { correct++; }

    /* Many delete/insert cycles on a small map: tombstones are recycled, the
     * table does not grow, and missing-key lookups still terminate. */
    gKeyFrees = 0;
    gValueFrees = 0;
    map.StrMap *cyc = map.strNew(8);
    cyc.setFreeKey(&countingFreeKey);
    cyc.setFreeValue(&countingFreeValue);
    cyc.set(dup("keep"), box(-1));
    bad = 0;
    for (i = 0; i < 20000; i++) {
        cyc.set(fmtFn("key-%d", i), box(i));
        str.formatTo(buf, "key-%d", i);
        if (!cyc.has(buf)) { bad++; }
        if (!cyc.delete(buf)) { bad++; }
        if (cyc.has(buf)) { bad++; }
        str.formatTo(buf, "never-%d", i);
        if (cyc.has(buf)) { bad++; }
    }
    tests++;
    if (bad == 0 && cyc.size == 1 && cyc.capacity <= 16 &&
            gKeyFrees == 20000 && gValueFrees == 20000) { correct++; }
    idx = 0;
    n = 0;
    while (cyc.iter(&idx, &node) && n < 10) {
        if (str.cmp(node.key, "keep")) { bad++; }
        n++;
    }
    tests++;
    if (bad == 0 && n == 1 && *((i64 *)cyc.get("keep")) == -1) { correct++; }
    cyc.release();
    tests++;
    if (gKeyFrees == 20001 && gValueFrees == 20001) { correct++; }

    /* map.IntMap: 10,000 keys, delete half, re-insert, iterate, release. */
    gValueFrees = 0;
    map.IntMap *im = map.intNew();
    im.setFreeValue(&countingFreeValue);
    for (i = 0; i < 10000; i++) {
        im.set(i * 7 - 3000, box(i));
    }
    bad = 0;
    for (i = 0; i < 10000; i += 2) {
        if (!im.delete(i * 7 - 3000)) { bad++; }
        if (im.delete(i * 7 - 3000)) { bad++; }
        if (im.delete(i * 7 - 2999)) { bad++; }
    }
    tests++;
    if (bad == 0 && im.size == 5000 && gValueFrees == 5000) { correct++; }

    idx = 0;
    n = 0;
    bad = 0;
    while (im.iter(&idx, &inode) && n <= 10000) {
        if (inode.key != (n * 2 + 1) * 7 - 3000 || *((i64 *)inode.value) != n * 2 + 1) {
            bad++;
        }
        n++;
    }
    tests++;
    if (bad == 0 && n == 5000) { correct++; }

    for (i = 0; i < 10000; i += 2) {
        im.set(i * 7 - 3000, box(i));
    }
    for (i = 1; i < 10000; i += 2) {
        im.set(i * 7 - 3000, box(i));
    }
    idx = 0;
    n = 0;
    bad = 0;
    while (im.iter(&idx, &inode) && n <= 20000) {
        n++;
    }
    for (i = 0; i < 10000; i++) {
        i64 *v = im.get(i * 7 - 3000);
        if (!v || *v != i || im.has(i * 7 - 2999)) { bad++; }
    }
    tests++;
    if (bad == 0 && n == 10000 && im.size == 10000 && gValueFrees == 10000) {
        correct++;
    }
    im.release();
    tests++;
    if (gValueFrees == 20000) { correct++; }

    /* map.IntMap collisions: keys 1, 33 and 65 share a probe chain in a 32-slot
     * map. Setting 65 after deleting 1 updates 65 instead of duplicating it. */
    map.IntMap *col = map.intNew(32);
    col.set(1, (void *)10);
    col.set(33, (void *)20);
    col.set(65, (void *)30);
    col.delete(1);
    col.set(65, (void *)31);
    col.set(0x7fffffffffffffff, (void *)40);
    idx = 0;
    n = 0;
    while (col.iter(&idx, &inode) && n < 10) {
        n++;
    }
    keys = col.keysToStr();
    tests++;
    if (n == 3 && col.size == 3 && (i64)col.get(65) == 31 &&
            !col.has(1) && (i64)col.get(0x7fffffffffffffff) == 40 &&
            !str.cmp(keys, "{33, 65, 9223372036854775807}")) { correct++; }
    mem.free(keys);
    col.set(1, (void *)11);
    tests++;
    if (col.size == 4 && (i64)col.get(1) == 11 &&
            col.delete(0x7fffffffffffffff) &&
            !col.has(0x7fffffffffffffff)) { correct++; }

    /* map.IntMap delete/insert cycles terminate and do not grow the table. */
    bad = 0;
    for (i = 0; i < 20000; i++) {
        col.set(1000 + i, null);
        if (!col.delete(1000 + i)) { bad++; }
        if (col.has(1000 + i) || col.has(-1 - i)) { bad++; }
    }
    tests++;
    if (bad == 0 && col.size == 3 && col.capacity <= 64) { correct++; }
    col.release();

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
