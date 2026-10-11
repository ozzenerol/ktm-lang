import list, test;

/* New list functions: ListClear (free the nodes, keep the head - also a
 * ListInit stack head), ListRemove (unlink + free one node, return its
 * value), ListFirst/ListLast (peek). Checked against an array model. */

i64 gSeed = 7;
i64 nextRand() {
    gSeed = (gSeed * 6364136223846793005 + 1442695040888963407);
    return (gSeed >> 33) & 0x7FFFFFFF;
}

i64 gFreed, gFreedSum;
void countFree(void *v) { gFreed++; gFreedSum += (i64)v; }

const i64 MODEL_MAX = 4096;

/* The list holds model[0..n) in order, linked both ways */
bool matches(list.List *l, i64 *model, i64 n) {
    list.List *it = l.next;
    for (i64 i = 0; i < n; ++i) {
        if (it == l || (i64)it.value != model[i] || it.next.prev != it) { return false; }
        it = it.next;
    }
    if (it != l || l.prev.next != l) { return false; }
    if (n == 0) { return l.first() == null && l.last() == null && l.isEmpty(); }
    return (i64)l.first() == model[0] && (i64)l.last() == model[n - 1];
}

list.List *nodeAt(list.List *l, i64 idx) {
    list.List *it = l.next;
    while (idx-- > 0) { it = it.next; }
    return it;
}

bool modelRun(list.List *l, i64 ops) {
    i64 model[MODEL_MAX];
    i64 n = 0, nextVal = 1;
    for (i64 op = 0; op < ops; ++op) {
        i64 r = nextRand() % 100;
        if (r < 35 && n < MODEL_MAX) {
            l.append((void *)nextVal);
            model[n++] = nextVal++;
        } else if (r < 60 && n < MODEL_MAX) {
            l.prepend((void *)nextVal);
            for (i64 i = n; i > 0; --i) { model[i] = model[i - 1]; }
            model[0] = nextVal++;
            n++;
        } else if (r < 95 && n > 0) {
            i64 idx = nextRand() % n;
            i64 v = (i64)nodeAt(l, idx).remove();
            if (v != model[idx]) { return false; }
            for (i64 i = idx; i < n - 1; ++i) { model[i] = model[i + 1]; }
            n--;
        } else if (r >= 99) {
            gFreed = 0;
            gFreedSum = 0;
            i64 sum = 0;
            for (i64 i = 0; i < n; ++i) { sum += model[i]; }
            l.clear(&countFree);
            if (gFreed != n || gFreedSum != sum) { return false; }
            n = 0;
        }
        if (!matches(l, model, n)) { return false; }
    }
    return true;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - list clear/remove/first/last: ");

    list.List *l = list.new();
    tests++; if (modelRun(l, 20000)) { correct++; }
    else { printf("model mismatch\n"); }
    l.release(null);

    /* A ListInit head on the stack: clear it, reuse it, clear it again */
    list.List head;
    head.init();
    for (i64 i = 1; i <= 5; ++i) { head.append((void *)i); }
    gFreed = 0;
    gFreedSum = 0;
    head.clear(&countFree);
    bool ok = gFreed == 5 && gFreedSum == 15 && head.isEmpty() &&
                        head.count() == 0;
    head.append((void *)9);
    ok = ok && (i64)head.first() == 9 && (i64)head.last() == 9;
    head.clear();  /* no callback */
    ok = ok && head.isEmpty();
    tests++; if (ok) { correct++; }

    /* Removing the only node, and the guards */
    list.List *one = list.new();
    one.append((void *)42);
    ok = (i64)one.next.remove() == 42 && one.isEmpty();
    ok = ok && one.remove() == null && one.isEmpty();  /* empty head: untouched */
    ok = ok && list.List.remove(null) == null && list.List.first(null) == null &&
             list.List.last(null) == null;
    list.List.clear(null, &countFree);
    one.release(null);
    tests++; if (ok) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
