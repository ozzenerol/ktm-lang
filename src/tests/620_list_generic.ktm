import list, mem, str, test;

/* list.List<T> (v0.10.0), replacing the void * list: append/prepend/
 * insertBefore/insertAfter return the node and remove(node) takes it out;
 * shift/pop/first/last with their ok flag; merges with empty lists (the
 * old merge linked a freed head in); a 20000-step random model run
 * checking both directions of the links; struct values; ad frees it. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* l holds want[0..n), linked correctly both ways, len right */
bool holds(list.List<i64> *l, i64 *want, i64 n) {
    auto it = l.head;
    list.Node<i64> *prev = null;
    for (i64 i = 0; i < n; ++i) {
        if (!it || it.value != want[i] || it.prev != prev) { return false; }
        prev = it;
        it = it.next;
    }
    if (it || l.tail != prev || l.len != n) { return false; }
    return n == 0 ? !l.head : l.head.prev == null && l.tail.next == null;
}

list.List<i64> make(i64 from, i64 n) {
    list.List<i64> l = list.new<i64>();
    for (i64 i = 0; i < n; ++i) { l.append(from + i); }
    return l;
}

bool mergeCase(i64 n1, i64 n2, bool append) {
    list.List<i64> l1 = make(1, n1), l2 = make(100, n2);
    i64 want[18];
    i64 n = 0;
    if (append) {
        for (i64 i = 0; i < n1; ++i) { want[n++] = 1 + i; }
        for (i64 i = 0; i < n2; ++i) { want[n++] = 100 + i; }
        l1.mergeAppend(&l2);
    } else {
        for (i64 i = 0; i < n2; ++i) { want[n++] = 100 + i; }
        for (i64 i = 0; i < n1; ++i) { want[n++] = 1 + i; }
        l1.mergePrepend(&l2);
    }
    bool ok = holds(&l1, want, n) && l2.len == 0 && !l2.head && !l2.tail;
    l1.append(500);
    l1.prepend(0);
    for (i64 i = n; i > 0; --i) { want[i] = want[i - 1]; }
    want[0] = 0;
    want[n + 1] = 500;
    ok = ok && holds(&l1, want, n + 2);
    l1.free();
    l2.free();
    return ok;
}

i64 gSeed = 7;
i64 nextRand() {
    gSeed = gSeed * 6364136223846793005 + 1442695040888963407;
    return (gSeed >> 33) & 0x7FFFFFFF;
}

const i64 MODEL_MAX = 512;

list.Node<i64> *nodeAt(list.List<i64> *l, i64 idx) {
    auto it = l.head;
    while (idx-- > 0) { it = it.next; }
    return it;
}

bool modelRun(i64 ops) {
    scoped list.List<i64> l = list.new<i64>();
    i64 model[MODEL_MAX];
    i64 n = 0, nextVal = 1;
    for (i64 op = 0; op < ops; ++op) {
        i64 r = nextRand() % 100;
        if (r < 25 && n < MODEL_MAX) {
            l.append(nextVal);
            model[n++] = nextVal++;
        } else if (r < 45 && n < MODEL_MAX) {
            l.prepend(nextVal);
            for (i64 i = n; i > 0; --i) { model[i] = model[i - 1]; }
            model[0] = nextVal++;
            n++;
        } else if (r < 60 && n > 0 && n < MODEL_MAX) {
            i64 idx = nextRand() % n;
            bool before = nextRand() % 2;
            auto at = nodeAt(&l, idx);
            auto made = before ? l.insertBefore(at, nextVal) : l.insertAfter(at, nextVal);
            if (made.value != nextVal) { return false; }
            i64 pos = before ? idx : idx + 1;
            for (i64 i = n; i > pos; --i) { model[i] = model[i - 1]; }
            model[pos] = nextVal++;
            n++;
        } else if (r < 97 && n > 0) {
            i64 idx = nextRand() % n;
            if (l.remove(nodeAt(&l, idx)) != model[idx]) { return false; }
            for (i64 i = idx; i < n - 1; ++i) { model[i] = model[i + 1]; }
            n--;
        } else if (r >= 99) {
            l.clear();
            n = 0;
        }
        if (!holds(&l, model, n)) { return false; }
        bool ok1, ok2;
        i64 f = l.first(&ok1), z = l.last(&ok2);
        if (n && (!ok1 || !ok2 || f != model[0] || z != model[n - 1])) { return false; }
        if (!n && (ok1 || ok2 || f != 0 || z != 0)) { return false; }
    }
    return true;
}

struct Job { i64 id; u8[] name; };

i32 main() {
    printf("Test - list.List<T>: ");

    /* FIFO with append + shift, LIFO with append + pop */
    scoped list.List<i64> q = list.new<i64>();
    bool ok;
    check(q.shift(&ok) == 0 && !ok && q.pop(&ok) == 0 && !ok && q.len == 0, "empty: zero value, ok false");
    q.append(1);
    q.append(2);
    q.append(3);
    check(q.shift() == 1 && q.shift() == 2 && q.shift(&ok) == 3 && ok && q.len == 0, "append + shift is FIFO");
    bool fifo = true;
    i64 in = 0, out = 0;
    for (i64 i = 0; i < 50; i++) {
        q.append(in++);
        q.append(in++);
        if (q.shift() != out++) { fifo = false; }
    }
    while (q.len) { if (q.shift() != out++) { fifo = false; } }
    check(fifo && out == 100, "interleaved appends and shifts");
    q.append(1);
    q.append(2);
    q.append(3);
    q.prepend(0);
    check(q.pop() == 3 && q.shift() == 0 && q.first() == 1 && q.last() == 2 && q.len == 2,
          "pop from the back, prepend at the front, first/last");

    /* the node from append removes it later in O(1) */
    auto two = q.head.next;
    auto made = q.append(9);
    check(q.remove(made) == 9 && q.remove(two) == 2 && q.len == 1 && q.tail == q.head, "remove(node)");

    /* merges, every size against every size, both ways */
    bool merges = true;
    i64 sizes[3] = {0, 1, 3};
    for (i64 a = 0; a < 3; ++a) {
        for (i64 b = 0; b < 3; ++b) {
            if (!mergeCase(sizes[a], sizes[b], true) || !mergeCase(sizes[a], sizes[b], false)) {
                merges = false;
            }
        }
    }
    check(merges, "mergeAppend/mergePrepend with empty and short lists");

    check(modelRun(20000), "20000 random appends, prepends, inserts, removes and clears");

    /* struct values, walking with a for loop */
    scoped list.List<Job> jobs = list.new<Job>();
    Job a = {1, "build"}, b = {2, "test"};
    jobs.append(a);
    jobs.prepend(b);
    String names = str.new();
    for (auto n = jobs.head; n; n = n.next) { names.appendFormat("%d:%s ", n.value.id, n.value.name); }
    check(str.equals(names, "2:test 1:build "), "struct values in order");
    names.free();
    Job first = jobs.shift();
    check(first.id == 2 && jobs.len == 1, "shift a struct");

    /* pointers as values: the list frees its nodes, not what they point to */
    scoped list.List<i64 *> ptrs = list.new<i64 *>();
    i64 *p = mem.alloc(sizeof(i64));
    *p = 41;
    ptrs.append(p);
    i64 *back = ptrs.pop();
    check(back == p && *back == 41 && ptrs.pop() == null, "pointer values; empty pop is null");
    mem.free(p);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
