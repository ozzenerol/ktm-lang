import cstr, mem, set, test, vec;

/* set.IntSet stored nothing: set.intAdd is `return map.rbSet(...);` in a void
 * function and the compiler dropped the call (fixed in the compiler, test
 * 185). This checks set.IntSet end to end. Deleting is covered by test 186. */

i64 gCorrect = 0;
void check(bool ok, u8 *what) {
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

set.IntSet *setOf(i64 *keys, i64 n) {
    set.IntSet *s = set.intNew();
    for (i64 i = 0; i < n; i++) { s.add(keys[i]); }
    return s;
}

/* The set's keys, in iteration order, as "a,b,c" */
u8 *keysFn(set.IntSet *s) {
    u8 *buf = cstr.dup("");
    set.IntSetIter *it = s.iterNew();
    set.IntSetEntry *e;
    bool first = true;
    while ((e = set.intIterNext(it))) {
        if (!first) { buf = cstr.appendFormat(buf, ","); }
        buf = cstr.appendFormat(buf, "%d", e.key);
        first = false;
    }
    set.intIterRelease(it);
    return buf;
}

bool keysAre(set.IntSet *s, u8 *want) {
    u8 *got = keysFn(s);
    bool ok = !cstr.cmp(got, want);
    if (!ok) { printf("\n  keys '%s', want '%s'\n", got, want); }
    mem.free(got);
    return ok;
}

i32 main() {
    i64 tests = 28, i;
    i64 aKeys[6] = {5, -3, 9, 0, 5, I64_MIN};
    i64 bKeys[4] = {9, 1, -3, I64_MAX};
    set.IntSet *empty = set.intNew(), *a, *b, *u, *d, *sd, *in, *big;
    u8 *strVal;
    printf("Test - IntSet: ");

    check(empty.size() == 0 && !empty.has(0), "empty set");
    check(keysAre(empty, ""), "empty iteration");

    a = setOf(aKeys, 6);            /* 5 twice */
    b = setOf(bKeys, 4);
    check(a.size() == 5, "size ignores duplicates");
    check(a.has(5) && a.has(-3) && a.has(0) && a.has(I64_MIN), "has");
    check(!a.has(1) && !a.has(-5) && !a.has(I64_MAX), "has not");
    check(keysAre(a, "-9223372036854775808,-3,0,5,9"), "sorted iteration");

    u = a.unionWith(b);
    check(u.size() == 7, "union size");
    check(keysAre(u, "-9223372036854775808,-3,0,1,5,9,9223372036854775807"), "union");
    d = a.diff(b);
    check(keysAre(d, "-9223372036854775808,0,5"), "a - b");
    d.release();
    d = b.diff(a);
    check(keysAre(d, "1,9223372036854775807"), "b - a");
    sd = a.symDiff(b);
    check(keysAre(sd, "-9223372036854775808,0,1,5,9223372036854775807"), "symmetric difference");
    in = a.intersection(b);
    check(keysAre(in, "-3,9"), "intersection");
    check(a.unionWith(empty).size() == 5 && a.diff(empty).size() == 5 &&
                a.intersection(empty).size() == 0 && empty.diff(a).size() == 0,
                "with the empty set");

    check(a.eq(a) && !a.eq(b), "equality");
    set.IntSet *a2 = setOf(aKeys, 6);
    check(a.eq(a2), "equal sets built separately");
    a2.add(42);
    check(!a.eq(a2), "one extra key");

    vec.IntVec *v = in.toVec();
    check(v.size == 2 && v.entries[0] == -3 && v.entries[1] == 9, "IntSetToVec");

    strVal = in.toStr();
    check(!cstr.cmp(strVal, "{-3, 9}"), "IntSetToString");
    if (cstr.cmp(strVal, "{-3, 9}")) { printf("  got '%s'\n", strVal); }
    mem.free(strVal);
    strVal = empty.toStr();
    check(!cstr.cmp(strVal, "{}"), "IntSetToString of an empty set");
    if (cstr.cmp(strVal, "{}")) { printf("  got '%s'\n", strVal); }
    mem.free(strVal);
    set.IntSet *one = set.intNew();
    one.add(7);
    strVal = one.toStr();
    check(!cstr.cmp(strVal, "{7}"), "IntSetToString of one key");
    mem.free(strVal);

    /* a big set, keys added in a scrambled order, half negative */
    big = set.intNew();
    for (i = 0; i < 2000; i++) { big.add((i * 7919) % 2000 - 1000); }
    for (i = 0; i < 2000; i++) { big.add((i * 31) % 2000 - 1000); } /* again */
    check(big.size() == 2000, "big set size");
    bool ok = true;
    for (i = -1000; i < 1000; i++) { if (!big.has(i)) { ok = false; } }
    check(ok && !big.has(1000) && !big.has(-1001), "big set has");
    set.IntSetIter *it = big.iterNew();
    set.IntSetEntry *e;
    i64 count = 0, prev = -1001;
    ok = true;
    while ((e = set.intIterNext(it))) {
        if (e.key != prev + 1) { ok = false; }
        prev = e.key;
        count++;
    }
    set.intIterRelease(it);
    check(ok && count == 2000, "big set iterates in order");
    set.IntSet *evens = set.intNew();
    for (i = -1000; i < 1000; i += 2) { evens.add(i); }
    set.IntSet *odds = big.diff(evens);
    check(odds.size() == 1000 && odds.has(-999) && !odds.has(0), "big difference");
    check(odds.intersection(evens).size() == 0, "disjoint intersection");
    check(odds.unionWith(evens).eq(big), "union restores");
    check(big.symDiff(evens).size() == 1000, "big symmetric difference");

    a.release();
    b.release();
    u.release();
    big.release();
    check(true, "release");

    test.printResult(gCorrect, tests);
    printf("====\n");
    return 0;
}
