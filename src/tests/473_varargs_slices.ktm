import cstr, test;

/* Variadic parameters are slices (v0.3.0 step 3): in the body `T ...name`
 * is a `T[]` and `...name` a `u64[]` (64-bit slots), so name[i] is checked,
 * name can be sliced, ranged over and passed to a slice parameter, and
 * `...name` still passes them on. cstr.formatArgs takes the slots as a
 * slice: a format wanting more arguments than it got throws ERR_BOUNDS
 * instead of reading past them. `...x` spreads any slice of the extras'
 * type (an array too), evaluated once. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 total(i64[] s) { i64 t = 0; for (auto v : s) { t += v; } return t; }
i64 sum(i64 ...nums) { return total(nums); }
i64 sumOn(i64 ...nums) { return sum(...nums); }
i64 head(i64 ...nums) { i64[] two = nums[:2]; return two[0] * 10 + two[1]; }
i64 sumFirstTwo(i64 ...nums) { return sum(...nums[0:2]); }
i64 gCalls = 0;
i64[] counted(i64[] s) { gCalls++; return s; }
i64 last(i64 ...nums) { return nums[nums.len - 1]; }
u8 *types(i64 ...nums) { return typeof(nums); }
u8 *untypedType(...args) { return typeof(args); }
f64 avg(f64 ...xs) {
    if (!xs.len) { return 0; }
    f64 t = 0;
    for (i64 i = 0; i < xs.len; i++) { t += xs[i]; }
    return t / xs.len;
}
u8 *wrap(u8 *fmt, ...args) { return cstr.format("<%s>", cstr.formatArgs(null, fmt, args)); }
f64 secondAsFloat(...args) { return *(f64 *)&args[1]; }

i32 main() {
    printf("Test - variadic parameters are slices: ");
    check(sum() == 0 && sum(1, 2, 3) == 6 && sumOn(4, 5) == 9, "passed to a slice, spread on");
    check(head(7, 8, 9) == 78 && last(1, 2, 3) == 3, "sliced, indexed");
    i64 arr[4] = {1, 2, 3, 4};
    i64[] sl = arr;
    check(sumFirstTwo(5, 6, 7) == 11 && sum(...sl) == 10 && sum(...sl[1:]) == 9 &&
          sum(...arr) == 10, "spread a sub-slice, a slice, an array");
    check(sum(...counted(sl)) == 10 && gCalls == 1, "a spread evaluated once");
    u64 slots[2] = {7, 8};
    check(!cstr.cmp(wrap("%d+%d", ...slots), "<7+8>"), "spread u64 slots into untyped");
    check(!cstr.cmp(types(1), "i64[]") && !cstr.cmp(untypedType(1), "u64[]"), "typeof");
    check(avg(1, 2, 4) > 2.33 && avg(1, 2, 4) < 2.34 && avg() == 0, "f64 ...xs");
    check(!cstr.cmp(wrap("%d-%s-%.1f", 3, "x", 2.5), "<3-x-2.5>"), "formatArgs(null, fmt, args)");
    check(secondAsFloat(1, 2.5) == 2.5, "an untyped float slot");
    check(!cstr.cmp(cstr.concat("a", "b", "c"), "abc"), "cstr.concat");
    try {
        head(1);
        check(false, "a sub-slice past the extras");
    } catch (Error e) {
        check(e.code == ERR_BOUNDS && !cstr.cmp(e.message, "slice 0:2 out of range for length 1"),
              "a sub-slice past the extras");
    }
    try {
        last();
        check(false, "an index past the extras");
    } catch (Error e) {
        check(!cstr.cmp(e.message, "index -1 out of range for length 0"), "an index past the extras");
    }
    try {
        wrap("%d %d", 1);
        check(false, "a format wanting more than it got");
    } catch (Error e) {
        check(e.code == ERR_BOUNDS, "a format wanting more than it got");
    }
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
