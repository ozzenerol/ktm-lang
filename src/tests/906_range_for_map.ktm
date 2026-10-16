import map, str, test;

/* v0.27.1: a range for over a map.Map gives its live entries in insertion
 * order (deleted ones skipped), by value, through a pointer or as a field;
 * break and continue work; an empty map runs no pass. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

struct Holder { map.Map<u8[], i64> counts; };

i64 sumThrough(map.Map<i32, i64> *p) {
    i64 s = 0;
    for (auto e : p) { s += e.key * e.value; }
    return s;
}

i32 main() {
    printf("Test - range for over a map: ");

    map.Map<i32, i64> m = map.new<i32, i64>();
    for (i32 i = 1; i <= 6; i++) { m.set(i, i * 10); }
    m.delete(2);
    m.delete(5);
    i64 keys = 0, values = 0, passes = 0;
    for (auto e : m) {
        keys = keys * 10 + e.key;
        values += e.value;
        passes++;
    }
    check(keys == 1346, "insertion order, deleted entries skipped");
    check(values == 140 && passes == 4, "every live value once");
    check(sumThrough(&m) == 10 + 90 + 160 + 360, "through a pointer");

    i64 seen = 0;
    for (auto e : m) {
        if (e.key == 3) { continue; }
        if (e.key == 6) { break; }
        seen += e.key;
    }
    check(seen == 5, "continue and break");

    m.set(2, 99);
    i64 last = 0;
    for (auto e : m) { last = e.key; }
    check(last == 2, "a key set again after a delete comes last");

    Holder h;
    h.counts = map.new<u8[], i64>();
    h.counts.set("a", 1);
    h.counts.set("b", 2);
    h.counts.set("c", 3);
    h.counts.delete("a");
    String joined;
    for (auto e : h.counts) { joined.append(e.key); }
    check(str.equals(joined[0:], "bc"), "a map held in a struct, u8[] keys");
    joined.free();

    map.Map<i32, i32> empty = map.new<i32, i32>();
    i64 none = 0;
    for (auto e : empty) { none++; }
    check(none == 0, "an empty map");

    m.free();
    h.counts.free();
    empty.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
