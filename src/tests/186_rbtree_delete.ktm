import map, set, test;

/* map.rbDelete (and so set.intDelete) crashed: its search loop read `x->key`
 * with x null and never stopped on a match. It also tested for null where
 * the tree uses a sentinel node, the mirrored fix-up case coloured a node
 * red instead of black, and free_key/free_value got the node, not the
 * key/value. */

i64 rbTestCmp(void *a, void *b) {
    if ((i64)a < (i64)b) { return -1; }
    if ((i64)a > (i64)b) { return 1; }
    return 0;
}

/* The sentinel is the only node whose `left` is null */
bool isNil(map.RBNode *n) { return n.left == null; }

/* Returns the black height, or -1 if an invariant is broken: BST order,
 * no red node with a red child, same black height on every path, parent
 * links. */
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
    if (isNil(t.root) && t.size == 0) { return true; }
    if (t.root.color != 1) { return false; }  /* black root */
    if (check(t.root, null, I64_MIN, I64_MAX, &count) < 0) { return false; }
    return count == t.size;
}

i64 gFreed = 0, gFreedSum = 0;
void countFree(void *key) { gFreed++; gFreedSum += (i64)key; }

i32 main() {
    i64 correct = 0, tests = 17, i, k;
    bool ok;
    map.RBTree *t = map.rbNew(null, null, &rbTestCmp);
    printf("Test - RBTreeDelete: ");

    for (i = 1; i <= 7; i++) { t.set((void*)i, (void*)(i * 10)); }
    if (valid(t) && t.size == 7) { correct++; }

    t.delete((void*)100);                     /* absent */
    if (valid(t) && t.size == 7) { correct++; }

    k = (i64)t.root.key;                         /* the root (two children) */
    t.delete((void*)k);
    if (valid(t) && t.size == 6 && !t.has((void*)k)) { correct++; }

    t.delete((void*)1);                       /* a leaf */
    t.delete((void*)7);
    if (valid(t) && t.size == 4 && !t.has((void*)1) && !t.has((void*)7)) { correct++; }
    if ((i64)t.get((void*)3) == 30 && (i64)t.get((void*)6) == 60) { correct++; }

    t.delete((void*)7);                       /* already gone */
    if (valid(t) && t.size == 4) { correct++; }

    for (i = 1; i <= 7; i++) { t.delete((void*)i); } /* the rest */
    if (valid(t) && t.size == 0 && isNil(t.root)) { correct++; }

    t.set((void*)5, null);                 /* usable again */
    if (valid(t) && t.size == 1 && t.has((void*)5)) { correct++; }
    t.delete((void*)5);
    if (t.size == 0 && isNil(t.root)) { correct++; }

    /* Many keys in a scrambled order, deleted in another order */
    for (i = 0; i < 500; i++) { t.set((void*)((i * 37) % 500), null); }
    if (valid(t) && t.size == 500) { correct++; }
    ok = true;
    for (i = 0; i < 500; i += 2) {                 /* the even ones */
        t.delete((void*)((i * 101) % 500));
        if (i % 50 == 0 && !valid(t)) { ok = false; }
    }
    if (ok && valid(t)) { correct++; }
    ok = t.size == 250;
    /* exactly the keys (i*101)%500 for odd i are left */
    for (i = 1; i < 500; i += 2) { if (!t.has((void*)((i * 101) % 500))) { ok = false; } }
    if (ok) { correct++; }
    for (i = 499; i >= 0; i--) { t.delete((void*)i); }
    if (valid(t) && t.size == 0) { correct++; }

    /* free_key gets the key, once per deleted node */
    map.RBTree *t2 = map.rbNew(&countFree, null, &rbTestCmp);
    for (i = 1; i <= 10; i++) { t2.set((void*)i, null); }
    t2.delete((void*)4);
    t2.delete((void*)9);
    t2.delete((void*)42);
    if (gFreed == 2 && gFreedSum == 13 && valid(t2) && t2.size == 8) { correct++; }

    /* set.intDelete */
    set.IntSet *s = set.intNew();
    for (i = 0; i < 20; i++) { s.tree.set((void*)i, null); }
    s.delete(3);
    s.delete(0);
    s.delete(99);
    if (s.size() == 18 && !s.has(3) && !s.has(0) && s.has(4)) { correct++; }
    if (valid(s.tree)) { correct++; }
    for (i = 0; i < 20; i++) { s.delete(i); }
    if (s.size() == 0 && valid(s.tree)) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
