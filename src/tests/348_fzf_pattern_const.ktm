import cstr, fzf, mem, test;

/* fzf.match trimmed the pattern in place: the caller's string was changed, and
 * a string literal with leading or trailing spaces segfaulted under AOT
 * (literals are read-only). It works on a copy now. */

bool literal(u8 *text, i64 wantScore, i64 wantFirst) {
    fzf.Position *pos;
    bool ok;
    pos = fzf.match(text, "  ab  ");
    ok = pos && pos.score == wantScore && pos.size == 2 && pos.data[0] == wantFirst;
    fzf.release(pos);
    return ok;
}

bool unchanged(u8 *pat, u8 *text) {
    u8 *copy = cstr.dup(pat);
    fzf.Position *pos = fzf.match(text, copy);
    bool ok = !cstr.cmp(copy, pat);
    if (!ok) { printf("\n  [%s] became [%s]", pat, copy); }
    if (pos) { fzf.release(pos); }
    mem.free(copy);
    return ok;
}

i32 main() {
    printf("Test - fzf pattern const: ");
    i64 correct = 0, tests = 0;
    fzf.Position *pos;

    tests++; if (literal("abc", 56, 0)) { correct++; }

    pos = fzf.match("abc", "ab ");
    tests++; if (pos && pos.size == 2) { correct++; }
    fzf.release(pos);

    pos = fzf.match("abc", "   ");
    tests++; if (pos == null) { correct++; }

    pos = fzf.match("foo bar", "  foo\\ bar  ");
    tests++; if (pos && pos.score == 176 && pos.size == 7) { correct++; }
    fzf.release(pos);

    pos = fzf.match("abc", "  b   c ");
    tests++; if (pos && pos.score == 32 && pos.size == 2) { correct++; }
    fzf.release(pos);

    tests++; if (unchanged("ab  ", "abc")) { correct++; }
    tests++; if (unchanged("   ab", "abc")) { correct++; }
    tests++; if (unchanged("  x\\ y  ", "x y")) { correct++; }
    tests++; if (unchanged("   ", "abc")) { correct++; }

    if (correct != tests) { printf("\n"); }
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
