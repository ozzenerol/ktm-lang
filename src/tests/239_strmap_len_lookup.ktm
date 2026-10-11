import cstr, map, mem, test;

/* map.strGetLen/map.strHasLen compared `key_len` bytes but hashed the whole
 * NUL-terminated string, so a key given as the start of a longer buffer
 * (`("hello world", 5)`) was never found. map.StrMap now hashes exactly the
 * bytes it compares, with its own length-bounded hash and a final mix (the
 * old hash put similar keys like "key%d" in long runs of slots). Also
 * checks the empty key, keys with high bytes, and that iteration is still
 * in insertion order across resizes and deletes. */

i32 main() {
    printf("Test - StrMap length-bounded lookup: ");
    i64 tests = 0;
    i64 correct = 0;
    map.StrMap *m = map.strNew();
    m.set("hello", (void *)1);
    m.set("key", (void *)2);
    m.set("", (void *)3);
    m.set("caf\xc3\xa9", (void *)4);

    tests++;
    if (m.hasLen("hello world", 5) &&
            (i64)m.getLen("hello world", 5) == 1 &&
            (i64)m.getLen("key=value", 3) == 2 &&
            m.hasLen("key=value", 3)) {
        correct++;
            } else { printf("Failed - prefix of a longer buffer\n"); }

    tests++;
    if (!m.hasLen("hello world", 4) && !m.hasLen("hello world", 6) &&
            m.getLen("keys", 4) == null && !m.has("hell")) {
        correct++;
            } else { printf("Failed - near misses\n"); }

    /* The empty key: by length 0 from any buffer, and only once */
    m.set("", (void *)5);
    tests++;
    if (m.has("") && (i64)m.get("") == 5 &&
            (i64)m.getLen("xyz", 0) == 5 && m.size == 4) {
        correct++;
            } else { printf("Failed - empty key\n"); }

    tests++;
    if ((i64)m.getLen("caf\xc3\xa9!", 5) == 4 &&
            (i64)m.get("caf\xc3\xa9") == 4) {
        correct++;
            } else { printf("Failed - high bytes\n"); }
    m.release();

    /* Every key is found by Get, Has, GetLen and HasLen alike, also when it
     * is the start of a longer buffer; the map grows from 8 slots. */
    m = map.strNew(8);
    m.setFreeKey(&mem.free);
    i64 n = 20000, bad = 0;
    u8 buf[64];
    for (i64 i = 0; i < n; i++) {
        m.set(cstr.formatTo(null, "key%d", i), (void *)i);
    }
    for (i64 i = 0; i < n; i++) {
        cstr.formatTo(buf, "key%d-and-more", i);
        i64 len = cstr.len(buf) - 9;
        if ((i64)m.getLen(buf, len) != i || !m.hasLen(buf, len)) { bad++; }
        buf[len] = 0;
        if ((i64)m.get(buf) != i || !m.has(buf)) { bad++; }
    }
    tests++;
    if (bad == 0 && m.size == n) { correct++; }
    else { printf("Failed - %d lookups wrong\n", bad); }

    /* "key%d" keys spread out: no long runs of occupied slots */
    i64 run = 0, maxrun = 0;
    for (i64 i = 0; i < m.capacity; i++) {
        if (m.entries[i]) { run++; if (run > maxrun) { maxrun = run; } } else { run = 0; }
    }
    tests++;
    if (maxrun < 100) { correct++; }
    else { printf("Failed - longest run of occupied slots %d\n", maxrun); }

    /* Insertion order, after deleting every third key and growing again */
    for (i64 i = 0; i < n; i += 3) {
        cstr.formatTo(buf, "key%d", i);
        m.delete(buf);
    }
    for (i64 i = n; i < n + 5000; i++) {
        m.set(cstr.formatTo(null, "key%d", i), (void *)i);
    }
    i64 prev = -1;
    bad = 0;
    map.StrMapNode *node;
    for (i64 it = 0; m.iter(&it, &node);) {
        i64 v = (i64)node.value;
        if (v <= prev || (v < n && v % 3 == 0)) { bad++; }
        prev = v;
    }
    tests++;
    if (bad == 0) { correct++; }
    else { printf("Failed - iteration order (%d)\n", bad); }
    m.release();

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
