import cstr, mem, set, str, test, vec;

/* set.Set<T> (v0.10.0), replacing set.IntSet: add/has/delete/len, union,
 * diff, intersection, symmetric difference, subset and equality, walks in
 * the order values were added, items() as a Vec, u8[] values copied into
 * the set, struct values (hashed byte-wise), the i64 extremes, and 20000
 * random adds/deletes against an array. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* the set's values, sorted, as "a,b,c" */
bool sortedIs(set.Set<i64> *s, u8 *want) {
    vec.Vec<i64> v = s.items();
    v.sortAsc();
    String text = str.new();
    for (i64 i = 0; i < v.len; i++) { text.appendFormat(i ? ",%d" : "%d", v.get(i)); }
    bool ok = str.equals(text, cstr.view(want));
    if (!ok) { printf("\n  got {%s} want {%s}", text, want); }
    text.free();
    v.free();
    return ok;
}

struct Point { i64 x, y; };

i32 main() {
    printf("Test - set.Set<T>: ");

    scoped set.Set<i64> a = set.new<i64>();
    scoped set.Set<i64> b = set.new<i64>();
    scoped set.Set<i64> empty = set.new<i64>();
    check(a.add(5) && a.add(-3) && a.add(9) && !a.add(5) && a.len == 3, "add says whether it was new");
    a.add(I64_MIN);
    a.add(0);
    b.add(9);
    b.add(-3);
    b.add(1);
    b.add(I64_MAX);
    check(a.has(I64_MIN) && a.has(0) && !a.has(1) && a.len == 5, "has, the i64 extremes");
    check(sortedIs(&a, "-9223372036854775808,-3,0,5,9"), "items, sorted");

    scoped set.Set<i64> u = a.unionWith(&b);
    scoped set.Set<i64> d = a.diff(&b);
    scoped set.Set<i64> d2 = b.diff(&a);
    scoped set.Set<i64> sd = a.symDiff(&b);
    scoped set.Set<i64> in = a.intersection(&b);
    check(sortedIs(&u, "-9223372036854775808,-3,0,1,5,9,9223372036854775807"), "union");
    check(sortedIs(&d, "-9223372036854775808,0,5"), "a - b");
    check(sortedIs(&d2, "1,9223372036854775807"), "b - a");
    check(sortedIs(&sd, "-9223372036854775808,0,1,5,9223372036854775807"), "symmetric difference");
    check(sortedIs(&in, "-3,9"), "intersection");
    scoped set.Set<i64> ue = a.unionWith(&empty);
    scoped set.Set<i64> ie = a.intersection(&empty);
    check(ue.equals(&a) && ie.len == 0 && in.isSubset(&a) && in.isSubset(&b) && !a.isSubset(&b) &&
          empty.isSubset(&a), "with the empty set, subsets, equality");
    check(a.delete(5) && !a.delete(5) && a.len == 4 && !a.has(5), "delete says whether it was there");

    /* walks in insertion order */
    scoped set.Set<i64> order = set.new<i64>();
    order.add(30);
    order.add(10);
    order.add(20);
    order.delete(10);
    order.add(40);
    String walk = str.new();
    i64 it = 0, v;
    while (order.next(&it, &v)) { walk.appendFormat("%d ", v); }
    check(str.equals(walk, "30 20 40 "), "next() in the order values were added");
    walk.free();

    /* u8[] values are copied: the buffer can change after add */
    scoped set.Set<u8[]> words = set.new<u8[]>();
    u8 buf[8];
    mem.copy(buf, "pear", 5);
    words.add(buf[0:4]);
    mem.copy(buf, "fig\0", 5);
    words.add(buf[0:3]);
    check(words.has("pear") && words.has("fig") && !words.add("pear") && words.len == 2,
          "u8[] values copied into the set");
    check(words.delete("pear") && words.len == 1, "delete a u8[] value");

    /* struct values: the byte-wise fallback */
    scoped set.Set<Point> pts = set.new<Point>();
    Point p1 = {1, 2}, p2 = {1, 2}, p3 = {2, 1};
    pts.add(p1);
    check(pts.has(p2) && !pts.add(p2) && pts.add(p3) && pts.len == 2, "struct values");

    /* many values against an array */
    const i64 N = 3000;
    bool *ref = mem.allocZero(N);
    scoped set.Set<i64> big = set.new<i64>();
    u64 seed = 99;
    i64 n = 0;
    bool ok = true;
    for (i64 i = 0; i < 20000; i++) {
        seed = seed * 6364136223846793005 + 1442695040888963407;
        i64 k = (seed >> 33) % N;
        if ((seed >> 20) % 3 == 0) {
            if (big.delete(k) != ref[k]) { ok = false; }
            if (ref[k]) { n--; }
            ref[k] = false;
        } else {
            if (big.add(k) == ref[k]) { ok = false; }
            if (!ref[k]) { n++; }
            ref[k] = true;
        }
    }
    for (i64 k = 0; k < N; k++) { if (big.has(k) != ref[k]) { ok = false; } }
    check(ok && big.len == n, "20000 random adds and deletes match an array");
    mem.free(ref);
    big.clear();
    check(big.len == 0 && !big.has(1) && big.add(1), "clear, and reuse");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
