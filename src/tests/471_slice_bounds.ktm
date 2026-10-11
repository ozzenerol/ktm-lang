import cstr, fs, libc, proc, test;

/* Bounds checks (v0.3.0 step 2): `s[i]` (read, write, `+=`, `++`, `&`)
 * with i outside 0 .. len-1, and `s[a:b]` outside 0 <= a <= b <= len,
 * throw ERR_BOUNDS with the index or bounds and the length, at the line
 * of the access, so a catch can recover. Sub-slices are views: writing
 * through one writes the original. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* the message `body` throws, "" when it doesn't */
u8 *thrown(i64[] s, i64 what) {
    try {
        switch (what) {
            case 0: { i64 x = s[s.len]; }
            case 1: { s[-1] = 3; }
            case 2: { s[9] += 1; }
            case 3: { s[s.len]++; }
            case 4: { i64 *p = &s[s.len]; }
            case 5: { i64[] t = s[2:9]; }
            case 6: { i64[] t = s[3:1]; }
            case 7: { i64[] t = s[-1:]; }
            case 8: { i64[] t = s[:9]; }
        }
    } catch (Error e) {
        if (e.code != ERR_BOUNDS) { return "wrong code"; }
        return cstr.format("%s @%d", e.message, e.line);
    }
    return "";
}

i32 main() {
    printf("Test - slice bounds checks: ");
    i64 a[5] = {1, 2, 3, 4, 5};
    i64[] s = a;

    check(!cstr.cmp(thrown(s, 0), "index 5 out of range for length 5 @20"), "read past the end");
    check(!cstr.cmp(thrown(s, 1), "index -1 out of range for length 5 @21"), "negative write");
    check(!cstr.cmp(thrown(s, 2), "index 9 out of range for length 5 @22"), "+=");
    check(!cstr.cmp(thrown(s, 3), "index 5 out of range for length 5 @23"), "++");
    check(!cstr.cmp(thrown(s, 4), "index 5 out of range for length 5 @24"), "&s[i]");
    check(!cstr.cmp(thrown(s, 5), "slice 2:9 out of range for length 5 @25"), "end past the length");
    check(!cstr.cmp(thrown(s, 6), "slice 3:1 out of range for length 5 @26"), "start after the end");
    check(!cstr.cmp(thrown(s, 7), "slice -1:5 out of range for length 5 @27"), "negative start");
    check(!cstr.cmp(thrown(s, 8), "slice 0:9 out of range for length 5 @28"), "open start, end past");
    check(a[0] == 1 && a[4] == 5, "nothing written by a failed access");

    /* in range */
    i64[] mid = s[1:4];
    check(mid.len == 3 && mid[0] == 2 && mid[2] == 4, "s[1:4]");
    check(s[:2].len == 2 && s[3:].len == 2 && s[3:][1] == 5 && s[:].len == 5, "open ends");
    check(s[5:].len == 0 && s[2:2].len == 0, "empty sub-slices");
    mid[0] = 20;
    mid[2] += 10;
    check(a[1] == 20 && a[3] == 14, "a sub-slice is a view");
    i64 t = 0;
    for (auto v : s[1:3]) { t += v; }
    check(t == 23, "range-for over a sub-slice");
    i64[] inner = mid[1:];
    check(inner.len == 2 && inner[0] == 3 && inner.ptr == &a[2], "sub-slice of a sub-slice");

    /* uncaught: the program's report names it */
    u8 *src = "i32 main() {\n    i64 a[2] = {1, 2};\n    i64[] s = a;\n    return s[2];\n}\n";
    fs.writeAll("__471_src.ktm", cstr.view(src));
    u8 *out = proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                               "-jit __471_src.ktm 2>&1");
    check(out && libc.strstr(out, "index 2 out of range for length 2 (__471_src.ktm:4)"), "uncaught");
    fs.remove("__471_src.ktm");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
