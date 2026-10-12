import cstr, map, mem, str, test;

/* map.Tree<K, V> (v0.10.0), the ordered map replacing map.RBTree: random
 * sets and deletes against a plain array, with the red-black rules
 * checked after every round; in-order and reverse walks; u8[] and String
 * keys (copied, freed with the tree); f64 keys; a key type of our own
 * through a map.compare overload; clear and reuse. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* black height of n, -1 if a rule is broken below it */
i64 blackHeight(map.Tree<i64, i64> *t, map.TreeNode<i64, i64> *n, i64 lo, i64 hi) {
    if (n == t.nil) { return 1; }
    if (n.key <= lo || n.key >= hi) { return -1; }                  /* order */
    if (n.color == 0 && (n.left.color == 0 || n.right.color == 0)) { return -1; }  /* red-red */
    if (n.left != t.nil && n.left.parent != n) { return -1; }
    if (n.right != t.nil && n.right.parent != n) { return -1; }
    i64 l = blackHeight(t, n.left, lo, n.key), r = blackHeight(t, n.right, n.key, hi);
    if (l < 0 || l != r) { return -1; }
    return l + (n.color == 1 ? 1 : 0);
}

bool valid(map.Tree<i64, i64> *t) {
    if (!t.len) { return true; }
    return t.root.color == 1 && t.root.parent == null &&
           blackHeight(t, t.root, -1, 0x7fffffffffffffff) > 0;
}

struct Version { i64 major, minor; };
i64 map.compare(Version a, Version b) {
    if (a.major != b.major) { return a.major - b.major; }
    return a.minor - b.minor;
}

i32 main() {
    printf("Test - map.Tree: ");

    /* random against a reference: ref[k] = value + 1, 0 when absent */
    const i64 N = 2000;
    i64 *ref = mem.allocZero(sizeof(i64) * N);
    ad map.Tree<i64, i64> t = map.treeNew<i64, i64>();
    u64 seed = 12345;
    bool ok = true, rules = true;
    for (i64 round = 0; round < 20; round++) {
        for (i64 i = 0; i < 500; i++) {
            seed = seed * 6364136223846793005 + 1442695040888963407;
            i64 k = (seed >> 33) % N;
            if ((seed >> 20) % 3 == 0) {
                bool had = ref[k] != 0;
                if (t.delete(k) != had) { ok = false; }
                ref[k] = 0;
            } else {
                t.set(k, i);
                ref[k] = i + 1;
            }
        }
        if (!valid(&t)) { rules = false; }
    }
    i64 count = 0, prev = -1, v;
    for (i64 k = 0; k < N; k++) {
        if (ref[k]) {
            count++;
            if (!t.get(k, &v) || v != ref[k] - 1) { ok = false; }
        } else if (t.has(k)) {
            ok = false;
        }
    }
    check(ok && t.len == count, "20 x 500 random sets and deletes match an array");
    check(rules, "red-black rules hold after every round");
    bool ordered = true;
    i64 walked = 0;
    for (auto n = t.first(); n; n = t.after(n)) {
        if (n.key <= prev) { ordered = false; }
        prev = n.key;
        walked++;
    }
    check(ordered && walked == t.len, "first/after walk in key order");
    i64 back = 0;
    prev = N;
    for (auto n = t.last(); n; n = t.before(n)) {
        if (n.key >= prev) { ordered = false; }
        prev = n.key;
        back++;
    }
    check(ordered && back == t.len, "last/before walk backwards");

    /* delete during a walk: take the next node first */
    for (auto n = t.first(); n; ) {
        auto next = t.after(n);
        if (n.key % 2) { t.delete(n.key); }
        n = next;
    }
    bool evens = true;
    for (auto n = t.first(); n; n = t.after(n)) { if (n.key % 2) { evens = false; } }
    check(evens && valid(&t), "deleting odd keys while walking");
    check(t.getOr(-5, 77) == 77 && !t.get(-5, &v) && !t.delete(-5), "a missing key");
    t.clear();
    check(t.len == 0 && !t.first() && !t.has(2), "clear");
    t.set(1, 10);
    check(t.len == 1 && t.getOr(1, 0) == 10 && valid(&t), "reused after clear");
    mem.free(ref);

    /* u8[] keys are copied: the caller's buffer can change after set */
    ad map.Tree<u8[], i64> words = map.treeNew<u8[], i64>();
    u8 buf[8];
    mem.copy(buf, "pear", 5);
    words.set(buf[0:4], 1);
    mem.copy(buf, "fig\0", 5);
    words.set(buf[0:3], 2);
    words.set("apple", 3);
    words.set("pear", 4);
    String order = str.new();
    for (auto n = words.first(); n; n = words.after(n)) { order.appendFormat("%s=%d ", n.key, n.value); }
    check(str.equals(order, "apple=3 fig=2 pear=4 "), "u8[] keys copied, ordered by bytes");
    order.free();
    check(words.delete("fig") && words.len == 2, "delete a u8[] key");

    ad map.Tree<String, i64> owned = map.treeNew<String, i64>();
    String k = str.from("zeta");
    owned.set(k, 1);
    k.free();
    String al = str.from("alpha");
    owned.set(al, 2);       /* the tree keeps its own copy */
    al.free();
    String z = str.from("zeta");
    check(owned.has(z) && str.equals(owned.first().key, "alpha"), "String keys");
    z.free();

    ad map.Tree<f64, u8 *> temps = map.treeNew<f64, u8 *>();
    temps.set(21.5, "warm");
    temps.set(-3.0, "cold");
    temps.set(37.0, "hot");
    check(temps.first().key == -3.0 && temps.last().value[0] == 'h', "f64 keys");

    ad map.Tree<Version, u8 *> rel = map.treeNew<Version, u8 *>();
    Version a = {0, 10}, b = {0, 9}, c = {1, 0};
    rel.set(a, "a");
    rel.set(b, "b");
    rel.set(c, "c");
    check(str.equals(cstr.view(rel.first().value), "b") && rel.last().key.major == 1,
          "a key type of our own through a map.compare overload");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
