import cstr, mem, set, test;

/* set.intToStr appended every element with cstr.appendFormat, which measures
 * the whole string each time: O(n^2), about 24 s for 500k elements. */

extern "c" i64 clock();

i64 digits(i64 v) {
    i64 n = 1;
    if (v < 0) { n++; v = -v; }
    while (v >= 10) { v /= 10; n++; }
    return n;
}

bool strIs(String got, u8[] want) {
    bool ok = got.equals(want);
    if (!ok) { printf("\n  got \"%s\" want \"%s\"\n", got, want); }
    got.free();
    return ok;
}

i32 main() {
    printf("Test - IntSetToString: ");
    i64 correct = 0, tests = 0, i, n, len, t;
    set.IntSet *s = set.intNew();
    String strVal;

    tests++; if (strIs(s.toStr(), "{}")) { correct++; }
    s.add(42);
    tests++; if (strIs(s.toStr(), "{42}")) { correct++; }
    s.add(-7);
    s.add(0);
    s.add(42);
    tests++; if (strIs(s.toStr(), "{-7, 0, 42}")) { correct++; }
    s.add(I64_MIN);
    s.add(I64_MAX);
    tests++; if (strIs(s.toStr(),
            "{-9223372036854775808, -7, 0, 42, 9223372036854775807}")) { correct++; }
    s.release();

    /* A large set: right length, right ends, and linear time (the old
     * code took several seconds here). */
    s = set.intNew();
    n = 300000;
    len = 2 + 2 * (n - 1);
    for (i = 0; i < n; i++) {
        s.add(n / 2 - i);
        len += digits(n / 2 - i);
    }
    t = clock();
    strVal = s.toStr();
    t = (clock() - t) / 1000; /* CLOCKS_PER_SEC is 1000000 on POSIX */
    tests++; if (strVal.len == len && strVal.ptr[len] == 0) { correct++; }
    tests++; if (strVal.startsWith("{-149999, -149998, ")) { correct++; }
    tests++; if (strVal.endsWith(", 149999, 150000}")) { correct++; }
    tests++;
    if (t < 2000) { correct++; }
    else { printf("\n  IntSetToString of %d elements took %d ms\n", n, t); }
    strVal.free();
    s.release();

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
