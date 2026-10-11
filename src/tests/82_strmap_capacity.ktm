import cstr, map, mem, test;

/* map.strNew and map.intNew rounded the capacity up to a power of two but
 * built the mask, the threshold and the entries array from the unrounded
 * value. Any other capacity - map.strNew(100), map.strNew(0) - gave a mask
 * whose probe sequence can't reach every slot, so inserting a few hundred
 * keys hung forever (and Release read past the entries array). Each map is
 * checked for a consistent mask before anything is inserted, so a
 * regression fails here instead of hanging. */

const i64 N_KEYS = 10000;

bool isPow2(u64 v) {
    return v != 0 && (v & (v - 1)) == 0;
}

bool strMapShapeOk(map.StrMap *m) {
    return isPow2(m.capacity) && m.mask == m.capacity - 1 &&
                 m.threshold > 0 && m.threshold < m.capacity;
}

bool intMapShapeOk(map.IntMap *m) {
    return isPow2(m.capacity) && m.mask == m.capacity - 1 &&
                 m.threshold > 0 && m.threshold < m.capacity;
}

i64 checkStrMap(map.StrMap *m, u8 **keys, u64 startCapacity) {
    i64 correct = 0, i, bad;

    if (!strMapShapeOk(m)) {
        /* Inserting into this map would loop forever; fail its checks. */
        return 0;
    }
    correct++;

    for (i = 0; i < N_KEYS; i++) {
        m.set(cstr.view(keys[i]), (void *)i);
    }

    bad = 0;
    for (i = 0; i < N_KEYS; i++) {
        if (!m.has(cstr.view(keys[i])) || (i64)m.get(cstr.view(keys[i])) != i) { bad++; }
    }
    if (bad == 0) { correct++; }

    if (m.size == N_KEYS) { correct++; }

    i64 idx = 0, count = 0;
    map.StrMapNode *node;
    bad = 0;
    while (m.iter(&idx, &node) && count <= N_KEYS) {
        if (!node.key.equals(cstr.view(keys[count])) || (i64)node.value != count) { bad++; }
        count++;
    }
    if (count == N_KEYS && bad == 0) { correct++; }

    if (!m.has("missing") && m.get("key-") == null &&
            !m.has("key-10000")) { correct++; }

    /* It grew, and the grown table is still consistent. */
    if (m.capacity > startCapacity && strMapShapeOk(m)) { correct++; }

    /* Replace every value under the same keys. */
    for (i = 0; i < N_KEYS; i++) {
        m.set(cstr.view(keys[i]), (void *)(i * 2));
    }
    bad = 0;
    for (i = 0; i < N_KEYS; i++) {
        if ((i64)m.get(cstr.view(keys[i])) != i * 2) { bad++; }
    }
    if (bad == 0 && m.size == N_KEYS) { correct++; }

    return correct;
}

i64 checkIntMap(map.IntMap *m, u64 startCapacity) {
    i64 correct = 0, i, bad;

    if (!intMapShapeOk(m)) {
        return 0;
    }
    correct++;

    for (i = 0; i < N_KEYS; i++) {
        m.set(i * 7, (void *)(i + 1));
    }

    bad = 0;
    for (i = 0; i < N_KEYS; i++) {
        if (!m.has(i * 7) || (i64)m.get(i * 7) != i + 1) { bad++; }
    }
    if (bad == 0) { correct++; }

    if (m.size == N_KEYS) { correct++; }

    i64 idx = 0, count = 0;
    map.IntMapNode *node;
    bad = 0;
    while (m.iter(&idx, &node) && count <= N_KEYS) {
        if (node.key != count * 7 || (i64)node.value != count + 1) { bad++; }
        count++;
    }
    if (count == N_KEYS && bad == 0) { correct++; }

    if (!m.has(3) && !m.has(N_KEYS * 7) && !m.has(-7)) { correct++; }

    if (m.capacity > startCapacity && intMapShapeOk(m)) { correct++; }

    return correct;
}

i32 main() {
    i64 correct = 0, tests = 0, i;
    u64 caps[7];
    caps[0] = 0;
    caps[1] = 1;
    caps[2] = 3;
    caps[3] = 100;
    caps[4] = 128;
    caps[5] = 1000;
    caps[6] = 1025;

    printf("Test - StrMapNew/IntMapNew with any capacity: ");

    u8 **keys = mem.alloc(N_KEYS * sizeof(u8 *));
    for (i = 0; i < N_KEYS; i++) {
        keys[i] = cstr.formatTo(null, "key-%d", i);
    }

    for (i = 0; i < 7; i++) {
        map.StrMap *sm = map.strNew(caps[i]);
        correct += checkStrMap(sm, keys, sm.capacity);
        tests += 7;
        /* Release walks `capacity` slots, which overran a mis-sized table. */
        if (strMapShapeOk(sm)) { sm.release(); }

        map.IntMap *im = map.intNew(caps[i]);
        correct += checkIntMap(im, im.capacity);
        tests += 6;
        if (intMapShapeOk(im)) { im.release(); }
    }

    /* Capacities are rounded up, never down. */
    map.StrMap *s100 = map.strNew(100);
    map.IntMap *i1000 = map.intNew(1000);
    if (s100.capacity == 128 && i1000.capacity == 1024 &&
            strMapShapeOk(s100) && intMapShapeOk(i1000)) {
        correct++;
        s100.release();
        i1000.release();
    }
    tests++;

    /* The default matches the one declared in std.ktmh. */
    map.StrMap *sd = map.strNew();
    map.IntMap *id = map.intNew();
    if (sd.capacity == 32 && sd.mask == 31 && strMapShapeOk(sd)) { correct++; }
    if (id.capacity == 32 && id.mask == 31 && intMapShapeOk(id)) { correct++; }
    tests += 2;

    /* The map copies its keys (and frees them on release). */
    u8 kbuf[32];
    for (i = 0; i < 500; i++) {
        sd.set(cstr.view(cstr.formatTo(kbuf, "owned-%d", i)), (void *)i);
    }
    if (sd.size == 500 && (i64)sd.get("owned-499") == 499) { correct++; }
    tests++;
    sd.release();
    id.release();

    for (i = 0; i < N_KEYS; i++) {
        mem.free(keys[i]);
    }
    mem.free(keys);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
