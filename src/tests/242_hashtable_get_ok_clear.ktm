import cstr, map, mem, test;

/* Hash table API additions and fixes:
 *  - map.intGet/map.strGet/map.strGetLen take an optional `bool *_ok`, true
 *    when the key is there, so a stored null or 0 can be told from a
 *    missing key (calls without it still work);
 *  - a pointer that is both the key and the value (`map.strSet(m,s,s)`)
 *    with both destructors registered was freed twice; it is now freed
 *    once, on release, delete, clear and update;
 *  - map.intClear/map.strClear remove every entry (running the
 *    destructors) and keep the capacity;
 *  - a null map or key no longer crashes: Get/Has give null/false,
 *    Set/Delete false.
 * Every allocation is counted against the frees (valgrind-clean). */

i64 gAllocs = 0;
i64 gKeyFrees = 0;
i64 gValueFrees = 0;

u8 *new(u8 *s) {
    gAllocs++;
    return cstr.dup(s);
}

void freeKey(void *p) {
    gKeyFrees++;
    mem.free(p);
}

void freeValue(void *p) {
    gValueFrees++;
    mem.free(p);
}

map.StrMap *countingMap() {
    map.StrMap *m = map.strNew();
    m.setFreeKey(&freeKey);
    m.setFreeValue(&freeValue);
    return m;
}

bool allFreed() {
    return gKeyFrees + gValueFrees == gAllocs;
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
            (i64)sm.getLen("one", 3, &okl) == 1 && okl &&
            sm.getLen("one", 2, &okml) == null && !okml &&
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

    /* map.strClear with destructors, shared and separate pointers */
    sm = countingMap();
    for (i64 i = 0; i < 40; i++) {
        u8 *k = cstr.formatTo(null, "k%d", i);
        gAllocs++;
        if (i % 2) { sm.set(k, k); }
        else       { sm.set(k, new("v")); }
    }
    cap = sm.capacity;
    sm.delete("k1");
    sm.delete("k2");
    sm.clear();
    tests++;
    if (allFreed() && sm.size == 0 && sm.capacity == cap && !sm.has("k3")) {
        correct++;
    } else { printf("Failed - StrMapClear: allocs %d, freed %d + %d\n",
                 gAllocs, gKeyFrees, gValueFrees);
    }
    sm.set(new("again"), new("v"));
    sm.release();
    tests++;
    if (allFreed()) { correct++; }
    else { printf("Failed - release after clear\n"); }

    /* Shared key and value: release, delete, and every kind of update */
    sm = countingMap();
    i64 freedBefore = gKeyFrees + gValueFrees;
    u8 *s = new("a");
    sm.set(s, s);               /* insert shared */
    sm.set(s, s);               /* same again: nothing freed */
    u8 *s2 = new("a");
    sm.set(s2, s2);             /* shared over shared: s freed once */
    tests++;
    if (gKeyFrees + gValueFrees == freedBefore + 1 &&
            sm.get("a") == s2) {
        correct++;
            } else { printf("Failed - shared over shared: %d %d %d\n", gKeyFrees, gValueFrees,
                 sm.get("a") == s2);
    }
    sm.set(new("a"), new("x")); /* separate over shared */
    u8 *s3 = new("a");
    sm.set(s3, s3);             /* shared over separate */
    sm.set(new("b"), new("y"));
    u8 *s4 = new("c");
    sm.set(s4, s4);
    sm.delete("c");             /* delete shared */
    sm.release();
    tests++;
    if (allFreed()) { correct++; }
    else { printf("Failed - shared keys: allocs %d, freed %d + %d\n",
                 gAllocs, gKeyFrees, gValueFrees);
    }

    /* null map and null key */
    ok = true;
    bool ok2 = true, ok3 = true;
    i64 cursor = 0;
    map.StrMapNode *snode;
    u8 *strVal;
    tests++;
    if (map.IntMap.get(null, 1, &ok) == null && !ok && !map.IntMap.has(null, 1) &&
            !map.IntMap.set(null, 1, null) && !map.IntMap.delete(null, 1) &&
            !map.IntMap.iter(null, &cursor, &inode) && !map.IntMap.resize(null) &&
            map.StrMap.get(null, "a", &ok2) == null && !ok2 && !map.StrMap.has(null, "a") &&
            !map.StrMap.set(null, "a", null) && !map.StrMap.delete(null, "a") &&
            !map.StrMap.hasLen(null, "a", 1) && !map.StrMap.iter(null, &cursor, &snode) &&
            !map.StrMap.resize(null)) {
        correct++;
            } else { printf("Failed - null map\n"); }
    map.IntMap.clear(null);
    map.StrMap.clear(null);
    map.IntMap.release(null);
    map.StrMap.release(null);
    map.IntMap.setFreeValue(null, &freeValue);
    map.StrMap.setFreeKey(null, &freeKey);
    map.StrMap.setFreeValue(null, &freeValue);
    strVal = map.IntMap.keysToStr(null);
    bool emptyStr = !cstr.cmp(strVal, "{}");
    mem.free(strVal);

    sm = map.strNew();
    sm.set("a", (void *)1);
    tests++;
    if (emptyStr && sm.get(null, &ok3) == null && !ok3 &&
            !sm.has(null) && !sm.set(null, (void *)2) &&
            !sm.delete(null) && sm.getLen(null, 3) == null &&
            !sm.hasLen(null, 0) && sm.size == 1) {
        correct++;
            } else { printf("Failed - null key\n"); }
    sm.release();

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
