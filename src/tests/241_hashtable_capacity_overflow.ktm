import map, mem, test;

/* map.intNew/map.strNew rounded the requested capacity up to a power of two
 * without a bound: above 2^63 (`map.intNew(-1)`) it wrapped to 0, the mask
 * became all ones and the first Set wrote far outside the table. A
 * negative request now gets the minimum capacity (8) and a huge one is
 * clamped to 2^28 slots; the map still grows past that as needed. */

i32 main() {
    printf("Test - hash table capacity bounds: ");
    i64 tests = 0;
    i64 correct = 0;

    map.IntMap *im = map.intNew(-1);
    im.set(12345, (void *)1);
    tests++;
    if (im.capacity == 8 && im.mask == 7 && (i64)im.get(12345) == 1) {
        correct++;
    } else { printf("Failed - IntMapNew(-1): capacity %d\n", im.capacity); }
    im.release();

    im = map.intNew(0x8000000000000001);
    tests++;
    if (im.capacity == 8) { correct++; }
    else { printf("Failed - IntMapNew(2^63 + 1): capacity %d\n", im.capacity); }
    im.release();

    map.StrMap *sm = map.strNew(-5);
    sm.set("a", (void *)1);
    tests++;
    if (sm.capacity == 8 && (i64)sm.get("a") == 1) { correct++; }
    else { printf("Failed - StrMapNew(-5): capacity %d\n", sm.capacity); }
    sm.release();

    /* Ordinary requests are rounded as before */
    im = map.intNew(0);
    sm = map.strNew(1000);
    tests++;
    if (im.capacity == 8 && sm.capacity == 1024 && sm.mask == 1023) { correct++; }
    else { printf("Failed - rounding: %d %d\n", im.capacity, sm.capacity); }
    im.release();
    sm.release();

    /* A huge request is clamped (or null when there isn't the memory for
     * the clamped table); the table is freed directly, without walking its
     * 2^28 slots */
    im = map.intNew(1 << 62);
    tests++;
    if (!im || (im.capacity == 1 << 28 && im.mask == (1 << 28) - 1)) {
        correct++;
    } else {
        printf("Failed - IntMapNew(2^62): capacity %d\n", im.capacity);
    }
    if (im) {
        mem.free(im.entries);
        im.indexes.release();
        mem.free(im);
    }

    /* A small map still grows */
    im = map.intNew(-1);
    for (i64 i = 0; i < 1000; i++) { im.set(i, (void *)i); }
    i64 bad = 0;
    for (i64 i = 0; i < 1000; i++) { if ((i64)im.get(i) != i) { bad++; } }
    tests++;
    if (bad == 0 && im.capacity >= 1024) { correct++; }
    else { printf("Failed - growth\n"); }
    im.release();

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
