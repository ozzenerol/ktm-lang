import list, test;

/* list.mergeAppend/list.mergePrepend with an empty l2 linked the freed l2
 * head into l1 (use after free, endless walks). list.pop, list.count and
 * list.release crashed on null while list.isEmpty and list.shift did not. */

list.List *make(i64 from, i64 n) {
    list.List *l = list.new();
    for (i64 i = 0; i < n; ++i) { l.append((void *)(from + i)); }
    return l;
}

/* `l` holds exactly want[0..n), linked correctly both ways */
bool holds(list.List *l, i64 *want, i64 n) {
    list.List *it = l.next;
    for (i64 i = 0; i < n; ++i) {
        if (it == l || (i64)it.value != want[i] || it.next.prev != it) { return false; }
        it = it.next;
    }
    if (it != l) { return false; }
    it = l.prev;
    for (i64 i = n - 1; i >= 0; --i) {
        if (it == l || (i64)it.value != want[i]) { return false; }
        it = it.prev;
    }
    return it == l && l.count() == n;
}

/* Merge an n1-list (1..) with an n2-list (100..), check, add one more
 * value at each end so a stale head would show, check again. */
bool mergeCase(i64 n1, i64 n2, bool append) {
    list.List *l1 = make(1, n1);
    list.List *l2 = make(100, n2);
    i64 want[16];
    i64 n = 0;
    if (append) {
        for (i64 i = 0; i < n1; ++i) { want[n++] = 1 + i; }
        for (i64 i = 0; i < n2; ++i) { want[n++] = 100 + i; }
        l1.mergeAppend(l2);
    } else {
        for (i64 i = 0; i < n2; ++i) { want[n++] = 100 + i; }
        for (i64 i = 0; i < n1; ++i) { want[n++] = 1 + i; }
        l1.mergePrepend(l2);
    }
    bool ok = holds(l1, want, n);
    l1.append((void *)500);
    l1.prepend((void *)0);
    i64 want2[18];
    want2[0] = 0;
    for (i64 i = 0; i < n; ++i) { want2[i + 1] = want[i]; }
    want2[n + 1] = 500;
    ok = ok && holds(l1, want2, n + 2);
    l1.release(null);
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - list merge with an empty list: ");

    bool ok = true;
    i64 sizes[3] = {0, 1, 3};
    for (i64 a = 0; a < 3; ++a) {
        for (i64 b = 0; b < 3; ++b) {
            if (!mergeCase(sizes[a], sizes[b], true)) {
                ok = false; printf("append %d + %d wrong\n", sizes[a], sizes[b]);
            }
            if (!mergeCase(sizes[a], sizes[b], false)) {
                ok = false; printf("prepend %d + %d wrong\n", sizes[a], sizes[b]);
            }
        }
    }
    tests++; if (ok) { correct++; }

    /* Merging into a list.init (stack) head */
    list.List stackHead;
    stackHead.init();
    stackHead.mergeAppend(make(7, 2));
    stackHead.mergePrepend(list.new());
    i64 w[2] = {7, 8};
    tests++; if (holds(&stackHead, w, 2)) { correct++; }
    stackHead.pop();
    stackHead.pop();

    /* null is safe everywhere, like list.isEmpty/list.shift */
    list.List *l = make(1, 2);
    l.mergeAppend(null);
    l.mergePrepend(null);
    list.List.mergeAppend(null, null);
    list.List.release(null, null);
    tests++;
    if (list.List.pop(null) == null && list.List.count(null) == 0 && list.List.shift(null) == null &&
            list.List.isEmpty(null) && l.count() == 2) { correct++; }
    l.release(null);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
