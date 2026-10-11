import cstr, fs, str, test;

/* String functions crashed on null. Now: cstr.cmp/cstr.cmpLen/cstr.cmpLenNoCase sort
 * null before every string (two NULLs are equal), cstr.firstOf/cstr.lastOf/
 * fs.ext return null, IsDotC/IsDotZ false, Atoi 0, the *Rem functions
 * leave dst empty, and cstr.copy with a null src copies "" (on arm64 too).
 * (cstr.globMatch got the same in its own fix; str's trims and splits
 * take a u8[], where an empty one is (null, 0).) */

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - string null safety: ");

    tests++;
    if (cstr.cmp(null, null) == 0 && cstr.cmp(null, "") < 0 && cstr.cmp("", null) > 0 &&
            cstr.cmp(null, "a") < 0 && cstr.cmp("a", null) > 0) { correct++; }
    tests++;
    if (cstr.cmpLen(null, null, 3) == 0 && cstr.cmpLen(null, "a", 3) < 0 &&
            cstr.cmpLen("a", null, 3) > 0 && cstr.cmpLenNoCase(null, null, 3) == 0 &&
            cstr.cmpLenNoCase(null, "A", 1) < 0 && cstr.cmpLenNoCase("a", null, 1) > 0) { correct++; }
    /* ordinary comparisons are unchanged */
    tests++;
    if (cstr.cmp("abc", "abd") < 0 && cstr.cmp("b", "a") > 0 && cstr.cmp("x", "x") == 0 &&
            cstr.cmpLen("abcX", "abcY", 3) == 0 && cstr.cmpLen("abc", "abd", 3) < 0 &&
            cstr.cmpLenNoCase("HeLLo", "hello", 5) == 0 && cstr.cmpLenNoCase("a", "B", 1) < 0 &&
            cstr.cmpLen("a", "b", 0) == 0) { correct++; }

    tests++;
    if (cstr.firstOf(null, ".") == null && cstr.firstOf("a.b", null) == null &&
            cstr.lastOf(null, ".") == null && cstr.lastOf("a.b", null) == null &&
            fs.ext(cstr.view(null)).len == 0 &&
            cstr.toInt(null) == 0 && cstr.toInt("42") == 42) { correct++; }

    u8 dst[16];
    /* fs.cutExt takes a u8[]: an empty one gives an empty one */
    bool ok = fs.cutExt(cstr.view(null)).len == 0;
    /* str.splitFirst/splitLast take a u8[] now: an empty one (null, 0) */
    u8[] none, before, after;
    ok = ok && !str.splitFirst(none, "/", &before, &after) && before.len == 0 && after.len == 0;
    ok = ok && !str.splitLast(none, "/", &before, &after) && before.len == 0 && after.len == 0;
    cstr.copy(dst, "junk");
    cstr.copy(dst, null);
    ok = ok && dst[0] == '\0';
    cstr.copy(null, "x");   /* does nothing */
    tests++; if (ok) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
