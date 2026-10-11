import cstr, map, mem, str, test;

/* Hash table API additions and fixes:
 *  - map.intGet/map.strGet take an optional `bool *_ok`, true when the
 *    key is there, so a stored null or 0 can be told from a missing key
 *    (calls without it still work);
 *  - map.intClear/map.strClear remove every entry (running the
 *    destructors) and keep the capacity;
 *  - a null map no longer crashes: Get/Has give null/false, Set/Delete
 *    false.
 * Since v0.3.0 a StrMap copies its u8[] keys and frees them itself (the
 * key destructor is gone), so a key can be a String freed right after.
 * Every value allocation is counted against the frees. */

i64 gAllocs = 0;
i64 gValueFrees = 0;

u8 *new(u8 *s) {
    gAllocs++;
    return cstr.dup(s);
}

void freeValue(void *p) {
    gValueFrees++;
    mem.free(p);
}

map.StrMap *countingMap() {
    map.StrMap *m = map.strNew();
    m.setFreeValue(&freeValue);
    return m;
}

bool allFreed() {
    return gValueFrees == gAllocs;
}

i32 main() {
    printf("Test - hash table _ok, Clear, shared keys and null: ");
    i64 tests = 0;
    i64 correct = 0;
    bool ok;

    /* _ok */
    map.IntMap *im = map.intNew();
    im.set(7, null);
    im.set(8, (void *)0);
    im.set(9, (void *)99);
    tests++;
    ok = true;
    bool ok7 = false, ok8 = false, ok9 = false;
    if (im.get(1, &ok) == null && !ok &&
            im.get(7, &ok7) == null && ok7 &&
            im.get(8, &ok8) == null && ok8 &&
            (i64)im.get(9, &ok9) == 99 && ok9 &&
            (i64)im.get(9) == 99) {
        correct++;
            } else { printf("Failed - IntMapGet _ok\n"); }

    map.StrMap *sm = map.strNew();
    sm.set("null", null);
    sm.set("one", (void *)1);
    tests++;
    bool okn = false, ok1 = false, okl = false, okm = true, okml = true;
    if (sm.get("null", &okn) == null && okn &&
            (i64)sm.get("one", &ok1) == 1 && ok1 &&
            sm.get("two", &okm) == null && !okm &&
            (i64)sm.get("one!"[0:3], &okl) == 1 && okl &&
            sm.get("one"[0:2], &okml) == null && !okml &&
            (i64)sm.get("one") == 1) {
        correct++;
            } else { printf("Failed - StrMapGet _ok\n"); }

    /* Clear keeps the capacity, empties iteration, and the map is reusable */
    for (i64 i = 0; i < 100; i++) { im.set(i * 3, (void *)i); }
    i64 cap = im.capacity;
    im.clear();
    i64 it = 0;
    map.IntMapNode *inode;
    bool iterEmpty = !im.iter(&it, &inode);
    im.set(5, (void *)6);
    tests++;
    if (im.size == 1 && im.capacity == cap && iterEmpty &&
            !im.has(9) && (i64)im.get(5) == 6) {
        correct++;
            } else { printf("Failed - IntMapClear\n"); }
    im.release();
    sm.release();

    /* map.intClear runs the value destructor, also after deletes */
    im = map.intNew();
    im.setFreeValue(&freeValue);
    for (i64 i = 0; i < 50; i++) { im.set(i, new("v")); }
    for (i64 i = 0; i < 50; i += 5) { im.delete(i); }
    im.clear();
    tests++;
    if (gValueFrees == 50 && im.size == 0) { correct++; }
    else { printf("Failed - IntMapClear destructors: %d\n", gValueFrees); }
    im.release();

    /* map.strClear with a value destructor; the keys are the map's */
    sm = countingMap();
    gValueFrees = 0;
    gAllocs = 0;
    for (i64 i = 0; i < 40; i++) {
        String k = str.format("k%d", i);
        sm.set(k, new("v"));
        k.free();               /* the map has its own copy */
    }
    cap = sm.capacity;
    sm.delete("k1");
    sm.delete("k2");
    tests++;
    if (gValueFrees == 2 && sm.has("k3") && (i64)sm.size == 38) { correct++; }
    else { printf("Failed - StrMap keys copied: %d %d\n", gValueFrees, sm.size); }
    sm.clear();
    tests++;
    if (allFreed() && sm.size == 0 && sm.capacity == cap && !sm.has("k3")) {
        correct++;
    } else { printf("Failed - StrMapClear: allocs %d, freed %d\n", gAllocs, gValueFrees);
    }
    sm.set("again", new("v"));
    sm.release();
    tests++;
    if (allFreed()) { correct++; }
    else { printf("Failed - release after clear\n"); }

    /* Updates free the replaced value once, never the same pointer set again */
    sm = countingMap();
    u8 *v1 = new("1");
    sm.set("a", v1);
    sm.set("a", v1);            /* same again: nothing freed */
    i64 freedBefore = gValueFrees;
    u8 *v2 = new("2");
    sm.set("a", v2);            /* v1 freed */
    tests++;
    if (gValueFrees == freedBefore + 1 && sm.get("a") == v2 && (i64)sm.size == 1) {
        correct++;
    } else { printf("Failed - update: %d %d\n", gValueFrees, freedBefore); }
    sm.set("b", new("y"));
    sm.delete("a");
    sm.release();
    tests++;
    if (allFreed()) { correct++; }
    else { printf("Failed - updates: allocs %d, freed %d\n", gAllocs, gValueFrees); }

    /* a null map, and the empty key */
    ok = true;
    bool ok2 = true, ok3 = true;
    i64 cursor = 0;
    map.StrMapNode *snode;
    tests++;
    if (map.IntMap.get(null, 1, &ok) == null && !ok && !map.IntMap.has(null, 1) &&
            !map.IntMap.set(null, 1, null) && !map.IntMap.delete(null, 1) &&
            !map.IntMap.iter(null, &cursor, &inode) && !map.IntMap.resize(null) &&
            map.StrMap.get(null, "a", &ok2) == null && !ok2 && !map.StrMap.has(null, "a") &&
            !map.StrMap.set(null, "a", null) && !map.StrMap.delete(null, "a") &&
            !map.StrMap.iter(null, &cursor, &snode) &&
            !map.StrMap.resize(null)) {
        correct++;
            } else { printf("Failed - null map\n"); }
    map.IntMap.clear(null);
    map.StrMap.clear(null);
    map.IntMap.release(null);
    map.StrMap.release(null);
    map.IntMap.setFreeValue(null, &freeValue);
    map.StrMap.setFreeValue(null, &freeValue);
    String strVal = map.IntMap.keysToStr(null);
    bool emptyStr = strVal.equals("{}");
    strVal.free();

    sm = map.strNew();
    sm.set("a", (void *)1);
    tests++;
    u8[] none;                  /* {null, 0}: the empty key, like "" */
    bool before = sm.has(none) || sm.get(none, &ok3) != null || ok3;
    sm.set(none, (void *)2);
    if (emptyStr && !before && (i64)sm.get("") == 2 && sm.has(none) &&
            sm.delete("") && !sm.has(none) && sm.size == 1) {
        correct++;
            } else { printf("Failed - empty key\n"); }
    sm.release();

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
