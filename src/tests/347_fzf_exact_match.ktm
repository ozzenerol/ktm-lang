import cstr, fzf, libc, mem, test;

/* The naive exact matcher (used for 'term, for every term when fuzzy is
 * false, and for !inverse terms) reset its state after the first matching
 * character instead of after a whole match, so only 1-character patterns
 * could match. Expected results are upstream telescope-fzf-native's. */

/* fzf.match's result as "score: positions" or "null", compared with the output
 * of upstream telescope-fzf-native (V1 algorithm) for the same input. */
bool check(u8 *text, u8 *pat, i32 case_mode, bool fuzzy, u8 *want) {
    u8 got[256], *p = cstr.dup(pat);
    i64 len;
    fzf.Position *pos = fzf.match(text, p, case_mode, fuzzy);
    if (!pos) {
        cstr.copy(got, "null");
    } else {
        len = libc.snprintf(got, sizeof(got), "%d:", pos.score);
        for (i64 i = 0; i < pos.size; i++) {
            len += libc.snprintf(got + len, sizeof(got) - len, " %d", pos.data[i]);
        }
        fzf.release(pos);
    }
    mem.free(p);
    if (cstr.cmp(got, want)) {
        printf("\n  FzF(\"%s\", \"%s\", %d, %d) = %s, want %s", text, pat, case_mode, fuzzy, got, want);
        return false;
    }
    return true;
}

i32 main() {
    printf("Test - fzf exact match: ");
    i64 correct = 0, tests = 0;

    tests++; if (check("foobar", "'oba", 0, true, "56: 2 3 4")) { correct++; }
    tests++; if (check("foobar", "oba", 0, false, "56: 2 3 4")) { correct++; }
    tests++; if (check("foobar", "!oba", 0, true, "null")) { correct++; }
    tests++; if (check("foobar", "'bar", 0, true, "56: 3 4 5")) { correct++; }
    tests++; if (check("hello world", "wor", 0, false, "80: 6 7 8")) { correct++; }
    tests++; if (check("src/main.c", "!main.c", 0, true, "null")) { correct++; }
    tests++; if (check("src/main.c", "'main !test", 0, true, "104: 4 5 6 7")) { correct++; }
    tests++; if (check("FooBar", "'oob", 2, true, "null")) { correct++; }
    tests++; if (check("FooBar", "'OOB", 1, true, "59: 1 2 3")) { correct++; }
    tests++; if (check("aab", "ab", 0, false, "36: 1 2")) { correct++; }
    tests++; if (check("ababc", "'abc", 0, true, "56: 2 3 4")) { correct++; }
    tests++; if (check("xabcabd", "abd", 0, false, "56: 4 5 6")) { correct++; }
    tests++; if (check("foo bar", "'o b", 0, true, "48: 1 4")) { correct++; }

    if (correct != tests) { printf("\n"); }
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
