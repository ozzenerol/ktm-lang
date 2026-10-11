import cstr, map, set, str, test;

/* v0.3.0 step 6e: a StrMap key is a u8[] the map copies and owns. Keys are
 * matched on all their bytes (a NUL inside a key is a byte like any other),
 * a key can be a String freed right after set() or a view into a reused
 * buffer, node.key / keyIter give views of the map's copy with a NUL after
 * it, and the copies are freed by delete, clear and release (no leak).
 * toStr/keysToStr of IntMap, StrMap and IntSet give Strings. */

String showValue(void *v) {
    return str.format("<%d>", (i64)v);
}

i32 main() {
    printf("Test - StrMap u8[] keys: ");
    i64 correct = 0, tests = 0;

    /* All the bytes count: "a\0b", "a\0c" and "a" are three keys */
    map.StrMap *m = map.strNew();
    m.set("a\0b", (void *)1);
    m.set("a\0c", (void *)2);
    m.set("a", (void *)3);
    tests++;
    if ((i64)m.size == 3 && (i64)m.get("a\0b") == 1 && (i64)m.get("a\0c") == 2 &&
            (i64)m.get("a") == 3 && !m.has("a\0")) { correct++; }
    else { printf("Failed - embedded NUL keys\n"); }
    m.release();

    /* A String key freed after set, a buffer reused for every key */
    m = map.strNew(8);
    u8 buf[32];
    for (i64 i = 0; i < 200; i++) {
        String k = str.format("key-%d", i);
        m.set(k, (void *)i);
        k.free();
        m.set(cstr.view(cstr.formatTo(buf, "buf-%d", i)), (void *)(i + 1000));
    }
    i64 bad = 0;
    for (i64 i = 0; i < 200; i++) {
        String k = str.format("key-%d!", i);
        if ((i64)m.get(k[0:k.len - 1]) != i) { bad++; }
        k.free();
        if ((i64)m.get(cstr.view(cstr.formatTo(buf, "buf-%d", i))) != i + 1000) { bad++; }
    }
    tests++;
    if (bad == 0 && (i64)m.size == 400) { correct++; }
    else { printf("Failed - copied keys: %d wrong\n", bad); }

    /* Views of the map's copies, NUL-terminated */
    i64 idx = 0, n = 0;
    u8[] key;
    bad = 0;
    while (m.keyIter(&idx, &key)) {
        if (key.ptr == buf || key.ptr[key.len] != 0 ||
                cstr.len(key.ptr) != key.len) { bad++; }
        n++;
    }
    tests++;
    if (bad == 0 && n == 400) { correct++; }
    else { printf("Failed - keyIter views\n"); }
    m.release();

    /* No leak: set, update, delete, clear and release free the copies */
    i64 before = test.heapInUse();
    for (i64 round = 0; round < 20; round++) {
        m = map.strNew();
        for (i64 i = 0; i < 500; i++) {
            m.set(cstr.view(cstr.formatTo(buf, "a-fairly-long-key-%d", i)), null);
            m.set(cstr.view(cstr.formatTo(buf, "a-fairly-long-key-%d", i)), (void *)1);
        }
        for (i64 i = 0; i < 500; i += 2) {
            m.delete(cstr.view(cstr.formatTo(buf, "a-fairly-long-key-%d", i)));
        }
        if (round % 2) { m.clear(); }
        m.release();
    }
    i64 delta = test.heapInUse() - before;
    tests++;
    if (delta < 65536) { correct++; }
    else { printf("Failed - keys leaked %d bytes\n", delta); }

    /* The printers give Strings */
    m = map.strNew();
    m.set("x", (void *)1);
    m.set("y", (void *)2);
    map.IntMap *im = map.intNew();
    im.set(5, (void *)50);
    set.IntSet *s = set.intNew();
    s.add(3);
    s.add(1);
    String a = m.keysToStr();
    defer a.free();
    String b = m.toStr(&showValue);
    defer b.free();
    String c = im.keysToStr();
    defer c.free();
    String d = im.toStr(&showValue);
    defer d.free();
    String e = s.toStr();
    defer e.free();
    tests++;
    if (a.equals("{x, y}") && b.equals("{[x] => <1>, [y] => <2>}") &&
            c.equals("{5}") && d.equals("{[5] => <50>}") && e.equals("{1, 3}")) {
        correct++;
    } else { printf("Failed - printers: %s %s %s %s %s\n", a, b, c, d, e); }
    m.release();
    im.release();
    s.release();

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
