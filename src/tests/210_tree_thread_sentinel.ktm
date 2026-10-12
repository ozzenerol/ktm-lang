import libc, map, mem, test;

/* All red-black trees shared one global sentinel node: making a tree reset
 * it and the transplant / delete fix-up wrote its parent, so trees used
 * from different threads corrupted each other. Each tree has its own
 * sentinel (map.Tree since v0.10.0, map.RBTree before). */

const i64 RBT_THREADS = 6;
const i64 RBT_KEYS = 300;
const i64 RBT_OPS = 200000;

extern "c" u32 alarm(u32 seconds);

/* Black height, or -1 if an invariant is broken */
i64 check(map.Tree<i64, i64> *t, map.TreeNode<i64, i64> *n, map.TreeNode<i64, i64> *parent,
          i64 lo, i64 hi, i64 *count) {
    if (n == t.nil) { return 1; }
    if (n.key <= lo || n.key >= hi) { return -1; }
    if (n.parent != parent) { return -1; }
    if (n.color == 0 && (n.left.color == 0 || n.right.color == 0)) { return -1; }
    i64 l = check(t, n.left, n, lo, n.key, count);
    i64 r = check(t, n.right, n, n.key, hi, count);
    if (l < 0 || r < 0 || l != r) { return -1; }
    ++*count;
    return l + (n.color == 1);
}

bool valid(map.Tree<i64, i64> *t) {
    i64 count = 0;
    if (!t.len) { return t.root == null || t.root == t.nil; }
    if (t.root.color != 1) { return false; }
    if (check(t, t.root, null, I64_MIN, I64_MAX, &count) < 0) { return false; }
    return count == t.len;
}

struct RbtArgs {
    libc.pthread_t *th;
    i64 seed;
    bool ok;
};

/* Random inserts and deletes against a shadow array, checking the tree
 * every so often. Every few hundred operations the tree is freed and a
 * new one made, so a tree is made while other threads use theirs. */
void *rbtWorker(RbtArgs *args) {
    bool shadow[RBT_KEYS];
    i64 i, k, n = 0, seed = args.seed, v;
    map.Tree<i64, i64> t = map.treeNew<i64, i64>();
    mem.set(shadow, 0, sizeof(shadow));
    args.ok = true;

    for (i = 0; i < RBT_OPS && args.ok; i++) {
        seed = seed * 6364136223846793005 + 1442695040888963407;
        k = ((seed >> 33) & 0x7FFFFFFF) % RBT_KEYS + 1;
        if ((seed >> 20) & 1) {
            t.set(k, k * 2);
            if (!shadow[k - 1]) { n++; }
            shadow[k - 1] = true;
        } else {
            t.delete(k);
            if (shadow[k - 1]) { n--; }
            shadow[k - 1] = false;
        }
        if (t.has(k) != shadow[k - 1] || t.len != n) { args.ok = false; }
        if (i % 97 == 0 && !valid(&t)) { args.ok = false; }
        if (i % 1500 == 1499) {
            t.free();
            t = map.treeNew<i64, i64>();
            mem.set(shadow, 0, sizeof(shadow));
            n = 0;
        }
    }
    if (!valid(&t)) { args.ok = false; }
    for (k = 1; k <= RBT_KEYS; k++) {
        if (shadow[k - 1] && (!t.get(k, &v) || v != k * 2)) { args.ok = false; }
    }
    t.free();
    return null;
}

i32 main() {
    printf("Test - tree sentinel per tree: ");
    i64 correct = 0, tests = 4, i, k, n;
    RbtArgs args[RBT_THREADS];

    alarm(120); /* a corrupted tree can loop forever: fail instead of hanging */

    /* Several trees on several threads at once */
    for (i = 0; i < RBT_THREADS; i++) {
        args[i].seed = i * 7919 + 1;
        args[i].ok = false;
        libc.pthread_create(&args[i].th,, (fn(void *) void *)(&rbtWorker), &args[i]);
    }
    for (i = 0; i < RBT_THREADS; i++) { libc.pthread_join(args[i].th); }
    bool ok = true;
    for (i = 0; i < RBT_THREADS; i++) { if (!args[i].ok) { ok = false; } }
    if (ok) { correct++; }

    /* Single thread, two trees interleaved: deleting from one doesn't
     * disturb the other */
    ad map.Tree<i64, i64> a = map.treeNew<i64, i64>();
    ad map.Tree<i64, i64> b = map.treeNew<i64, i64>();
    for (k = 1; k <= 200; k++) {
        a.set(k, k);
        b.set(k * 3, k);
        if (k % 3 == 0) {
            a.delete(k / 3);
            b.delete(k);
        }
    }
    if (valid(&a) && valid(&b) && a.len == 134 && b.len == 134) { correct++; }
    /* A new tree made while others hold nodes is empty and independent */
    ad map.Tree<i64, i64> c = map.treeNew<i64, i64>();
    c.set(1, 1);
    c.delete(1);
    if (c.len == 0 && valid(&c) && valid(&a) && valid(&b) && a.nil != b.nil && b.nil != c.nil) {
        correct++;
    }

    /* the walk is in order */
    ad map.Tree<i64, i64> t = map.treeNew<i64, i64>();
    for (k = 1; k <= 50; k++) { t.set((k * 37) % 101, 0); }
    i64 prev = -1;
    n = 0; ok = true;
    for (auto node = t.first(); node; node = t.after(node)) {
        if (node.key <= prev) { ok = false; }
        prev = node.key;
        n++;
    }
    if (ok && n == 50) { correct++; }

    alarm(0);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
