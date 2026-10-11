import cstr, mem, test;

/* "%d" of the smallest i64 printed punctuation: I64ToStr negated the number
 * (-I64_MIN overflows back to I64_MIN) and took `% 10` of a negative. */

i64 gCorrect = 0;

void expect(u8 *got, u8 *want) {
    if (!cstr.cmp(got, want)) { gCorrect++; }
    else { printf("\n  got '%s' want '%s'\n", got, want); }
    mem.free(got);
}

i32 main() {
    i64 tests = 19, min = I64_MIN, max = I64_MAX;
    u8 buf[64];
    printf("Test - %%d of the smallest i64: ");

    expect(cstr.formatTo(null, "%d", 0x8000000000000000), "-9223372036854775808");
    expect(cstr.formatTo(null, "%d", min), "-9223372036854775808");
    expect(cstr.formatTo(null, "%d", min + 1), "-9223372036854775807");
    expect(cstr.formatTo(null, "%d", max), "9223372036854775807");
    expect(cstr.formatTo(null, "%d", -1), "-1");
    expect(cstr.formatTo(null, "%d", -10), "-10");
    expect(cstr.formatTo(null, "%d", 0), "0");
    expect(cstr.formatTo(null, "%u", min), "9223372036854775808");
    expect(cstr.formatTo(null, "%u", -1), "18446744073709551615");

    /* widths, padding, justification */
    expect(cstr.formatTo(null, "%25d|", min), "     -9223372036854775808|");
    expect(cstr.formatTo(null, "%-25d|", min), "-9223372036854775808     |");
    expect(cstr.formatTo(null, "%025d", min), "-000009223372036854775808");
    expect(cstr.formatTo(null, "%5d", min), "-9223372036854775808");
    expect(cstr.formatTo(null, "%05d", -42), "-0042");
    expect(cstr.formatTo(null, "%-5d|", -42), "-42  |");
    expect(cstr.formatTo(null, "[%d,%d]", min, min), "[-9223372036854775808,-9223372036854775808]");

    /* the other ways of formatting */
    expect(cstr.formatTo(null, "%x", min), "8000000000000000");
    expect(cstr.format("%d", min), "-9223372036854775808");
    expect(cstr.appendFormat(cstr.dup("x="), "%d", min), "x=-9223372036854775808");

    test.printResult(gCorrect, tests);
    printf("====\n");
    return 0;
}
