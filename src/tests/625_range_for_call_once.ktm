import mem, str, test;

/* A range for over a slice that is not a variable or field (a call)
 * computes it once: it used to run the call again for the length and for
 * each element, 2n + 1 times, leaking what each call allocated. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 gCalls = 0;
i64[] numbers(i64 n) {
    gCalls++;
    i64[] s = {(i64 *)mem.alloc(sizeof(i64) * n), n};
    for (i64 i = 0; i < n; i++) { s[i] = i + 1; }
    return s;
}

i32 main() {
    printf("Test - range for over a call: ");

    i64 sum = 0;
    for (auto x : numbers(4)) { sum += x; }
    check(sum == 10 && gCalls == 1, "one call, every element");

    gCalls = 0;
    i64 seen = 0;
    for (auto x : numbers(5)) {
        if (x == 2) { continue; }
        if (x == 4) { break; }
        seen += x;
    }
    check(seen == 4 && gCalls == 1, "continue and break");

    gCalls = 0;
    i64 pairs = 0;
    for (auto a : numbers(3)) {
        for (auto b : numbers(2)) { pairs += a * b; }
    }
    check(pairs == 18 && gCalls == 4, "nested: the inner call once per outer element");

    gCalls = 0;
    i64 none = 0;
    for (auto x : numbers(0)) { none++; }
    check(none == 0 && gCalls == 1, "an empty slice");

    i64 words = 0;
    for (auto w : str.split("a b c", " ")) { if (w.len == 1) { words++; } }
    check(words == 3, "str.split in place");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
