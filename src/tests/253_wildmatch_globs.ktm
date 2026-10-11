import cstr, mem, test;

/* cstr.globMatch got simple globs wrong: ("banana","*a") was false,
 * ("abc","**") false, ("","*x") true, ("ab","ab*c") true - it checked the
 * end of the text too early, never backtracked properly and searched for
 * '?' / '*' literally after a '*'. It is now a standard glob match ('*'
 * any run, '?' any one character) with backtracking to the last '*',
 * checked against a recursive reference on random cases. */

/* Obviously-correct (exponential) reference */
bool refMatch(u8 *s, u8 *p) {
    if (*p == '\0') { return *s == '\0'; }
    if (*p == '*') {
        for (i64 i = 0; ; ++i) {
            if (refMatch(s + i, p + 1)) { return true; }
            if (s[i] == '\0') { return false; }
        }
    }
    if (*s == '\0') { return false; }
    if (*p == '?' || *p == *s) { return refMatch(s + 1, p + 1); }
    return false;
}

i64 gSeed = 99;
i64 nextRand() {
    gSeed = (gSeed * 6364136223846793005 + 1442695040888963407);
    return (gSeed >> 33) & 0x7FFFFFFF;
}

void randStr(u8 *buf, u8 *alphabet, i64 maxLen) {
    i64 n = nextRand() % (maxLen + 1), k = cstr.len(alphabet);
    for (i64 i = 0; i < n; ++i) { buf[i] = alphabet[nextRand() % k]; }
    buf[n] = '\0';
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - WildMatch globs: ");

    /* the audit's cases and the usual ones */
    tests++;
    if (cstr.globMatch("banana", "*a") && cstr.globMatch("abc", "**") && !cstr.globMatch("", "*x") &&
            !cstr.globMatch("ab", "ab*c") && cstr.globMatch("", "") && cstr.globMatch("", "*") &&
            !cstr.globMatch("a", "") && cstr.globMatch("main.ktm", "*.ktm") &&
            !cstr.globMatch("main.c", "*.ktm") && cstr.globMatch("abc", "a?c") &&
            !cstr.globMatch("ac", "a?c") && cstr.globMatch("mississippi", "m*iss*ppi") &&
            cstr.globMatch("aaaab", "*a*b") && !cstr.globMatch("aaaa", "*a*b") &&
            cstr.globMatch("x?y", "x?y") && cstr.globMatch("a*b", "a*b")) { correct++; }

    /* random cases against the reference */
    u8 s[16], p[16];
    i64 bad = 0;
    for (i64 i = 0; i < 20000; ++i) {
        randStr(s, "abc", 8);
        randStr(p, "ab*?c", 6);
        if (cstr.globMatch(s, p) != refMatch(s, p)) {
            if (bad < 10) { printf("mismatch (\"%s\", \"%s\")\n", s, p); }
            bad++;
        }
    }
    tests++; if (bad == 0) { correct++; }

    /* long input with many stars stays fast (no exponential backtracking) */
    u8 *longS = mem.alloc(20001);
    mem.set(longS, 'a', 20000);
    longS[20000] = '\0';
    tests++;
    if (!cstr.globMatch(longS, "*a*a*a*a*a*b") && cstr.globMatch(longS, "*a*a*a*a*a")) { correct++; }
    mem.free(longS);

    tests++; if (!cstr.globMatch(null, "*") && !cstr.globMatch("a", null)) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
