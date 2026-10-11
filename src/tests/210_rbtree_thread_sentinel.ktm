import libc, map, mem, set, test;

/* All red-black trees shared one global sentinel node: RBTreeNew reset
 * it and RBTransplant / the delete fix-up wrote its parent, so trees used
 * from different threads corrupted each other. Each tree now has its own
 * sentinel. */

const i64 RBT_THREADS = 6;
const i64 RBT_KEYS = 300;
const i64 RBT_OPS = 200000;

extern "c" u32 alarm(u32 seconds);

i64 rbtCmp(void *a, void *b) {
    if ((i64)a < (i64)b) { return -1; }
    if ((i64)a > (i64)b) { return 1; }
    return 0;
}

/* A tree's sentinel is the only node whose `left` is null */
bool isNil(map.RBNode *n) { return n.left == null; }

/* Black height, or -1 if an invariant is broken */
i64 check(map.RBNode *n, map.RBNode *parent, i64 lo, i64 hi, i64 *count) {
    if (isNil(n)) { return 1; }
    i64 k = (i64)n.key;
    if (k <= lo || k >= hi) { return -1; }
    if (n.parent != parent) { return -1; }
    if (n.color == 0 && (n.left.color == 0 || n.right.color == 0)) { return -1; }
    i64 l = check(n.left, n, lo, k, count);
    i64 r = check(n.right, n, k, hi, count);
    if (l < 0 || r < 0 || l != r) { return -1; }
    ++*count;
    return l + (n.color == 1);
}

bool valid(map.RBTree *t) {
    i64 count = 0;
    if (isNil(t.root)) { return t.size == 0; }
    if (t.root.color != 1) { return false; }
    if (check(t.root, null, I64_MIN, I64_MAX, &count) < 0) { return false; }
    return count == t.size;
}

struct RbtArgs {
    libc.pthread_t *th;
    i64 seed;
    bool ok;
};

/* Random inserts and deletes against a shadow array, checking the tree
 * every so often. Every few hundred operations the tree is released and
 * a new one made, so RBTreeNew runs while other threads use their trees. */
void *rbtWorker(RbtArgs *args) {
    bool shadow[RBT_KEYS];
    i64 i, k, n = 0, seed = args.seed;
    map.RBTree *t = map.rbNew(null, null, &rbtCmp);
    mem.set(shadow, 0, sizeof(shadow));
    args.ok = true;

    for (i = 0; i < RBT_OPS && args.ok; i++) {
        seed = seed * 6364136223846793005 + 1442695040888963407;
        k = ((seed >> 33) & 0x7FFFFFFF) % RBT_KEYS + 1;
        if ((seed >> 20) & 1) {
            t.set((void *)k, (void *)(k * 2));
            if (!shadow[k - 1]) { n++; }
            shadow[k - 1] = true;
        } else {
            t.delete((void *)k);
            if (shadow[k - 1]) { n--; }
            shadow[k - 1] = false;
        }
        if (t.has((void *)k) != shadow[k - 1] || t.size != n) { args.ok = false; }
        if (i % 97 == 0 && !valid(t)) { args.ok = false; }
        if (i % 1500 == 1499) {
            t.release();
            t = map.rbNew(null, null, &rbtCmp);
            mem.set(shadow, 0, sizeof(shadow));
            n = 0;
        }
    }
    if (!valid(t)) { args.ok = false; }
    for (k = 1; k <= RBT_KEYS; k++) {
        if (shadow[k - 1] && (i64)t.get((void *)k) != k * 2) { args.ok = false; }
    }
    t.release();
    return null;
}

i32 main() {
    printf("Test - RBTree sentinel per tree: ");
    i64 correct = 0, tests = 5, i, k, n;
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
    map.RBTree *a = map.rbNew(null, null, &rbtCmp);
    map.RBTree *b = map.rbNew(null, null, &rbtCmp);
    for (k = 1; k <= 200; k++) {
        a.set((void *)k, (void *)k);
        b.set((void *)(k * 3), (void *)k);
        if (k % 3 == 0) {
            a.delete((void *)(k / 3));
            b.delete((void *)k);
        }
    }
    if (valid(a) && valid(b) && a.size == 134 && b.size == 134) { correct++; }
    /* A new tree made while others hold nodes is empty and independent */
    map.RBTree *c = map.rbNew(null, null, &rbtCmp);
    if (c.size == 0 && isNil(c.root) && valid(a) && valid(b)) { correct++; }
    a.release();
    b.release();
    c.release();

    /* IntSet unchanged */
    set.IntSet *s = set.intNew();
    for (k = 10; k > 0; k--) { s.add(k); }
    s.delete(4);
    s.delete(99);
    set.IntSetIter *it = s.iterNew();
    set.IntSetEntry *e;
    n = 0;
    ok = true;
    i64 prev = 0;
    while ((e = set.intIterNext(it))) {
        if (e.key <= prev || e.key == 4) { ok = false; }
        prev = e.key;
        n++;
    }
    set.intIterRelease(it);
    if (ok && n == 9 && s.size() == 9 && s.has(10) && !s.has(4)) { correct++; }
    u8 *str = s.toStr();
    mem.free(str);
    s.release();

    /* RBIter walks in order */
    map.RBTree *t = map.rbNew(null, null, &rbtCmp);
    for (k = 1; k <= 50; k++) { t.set((void *)((k * 37) % 101), null); }
    map.RBIter *ri = t.iterNew();
    map.RBNode *node;
    n = 0; prev = -1; ok = true;
    while ((node = map.rbIterNext(ri))) {
        if ((i64)node.key <= prev) { ok = false; }
        prev = (i64)node.key;
        n++;
    }
    map.rbIterRelease(ri);
    if (ok && n == 50) { correct++; }
    t.release();

    alarm(0);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
