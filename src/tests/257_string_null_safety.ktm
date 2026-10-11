import fs, str, test;

/* String functions crashed on null. Now: str.cmp/str.cmpLen/str.cmpLenNoCase sort
 * null before every string (two NULLs are equal), str.firstOf/str.lastOf/
 * fs.ext return null, IsDotC/IsDotZ false, Atoi 0, the *Rem functions
 * leave dst empty, and str.copy with a null src copies "" (on arm64 too).
 * (str.trimStart/End and str.globMatch got the same in their own fixes.) */

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - string null safety: ");

    tests++;
    if (str.cmp(null, null) == 0 && str.cmp(null, "") < 0 && str.cmp("", null) > 0 &&
            str.cmp(null, "a") < 0 && str.cmp("a", null) > 0) { correct++; }
    tests++;
    if (str.cmpLen(null, null, 3) == 0 && str.cmpLen(null, "a", 3) < 0 &&
            str.cmpLen("a", null, 3) > 0 && str.cmpLenNoCase(null, null, 3) == 0 &&
            str.cmpLenNoCase(null, "A", 1) < 0 && str.cmpLenNoCase("a", null, 1) > 0) { correct++; }
    /* ordinary comparisons are unchanged */
    tests++;
    if (str.cmp("abc", "abd") < 0 && str.cmp("b", "a") > 0 && str.cmp("x", "x") == 0 &&
            str.cmpLen("abcX", "abcY", 3) == 0 && str.cmpLen("abc", "abd", 3) < 0 &&
            str.cmpLenNoCase("HeLLo", "hello", 5) == 0 && str.cmpLenNoCase("a", "B", 1) < 0 &&
            str.cmpLen("a", "b", 0) == 0) { correct++; }

    tests++;
    if (str.firstOf(null, ".") == null && str.firstOf("a.b", null) == null &&
            str.lastOf(null, ".") == null && str.lastOf("a.b", null) == null &&
            fs.ext(null) == null && !IsDotC(null) && !IsDotZ(null) &&
            str.toInt(null) == 0 && str.toInt("42") == 42) { correct++; }

    u8 dst[16];
    str.copy(dst, "junk");
    fs.cutExt(null, dst);
    bool ok = dst[0] == '\0';
    str.copy(dst, "junk");
    str.splitFirst(null, "/", dst);
    ok = ok && dst[0] == '\0';
    str.copy(dst, "junk");
    str.splitLast(null, "/", dst);
    ok = ok && dst[0] == '\0';
    str.copy(dst, "junk");
    str.copy(dst, null);
    ok = ok && dst[0] == '\0';
    str.copy(null, "x");   /* does nothing */
    tests++; if (ok) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
