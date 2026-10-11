import test;

import fzf, libc, mem, str;

/* `^prefix` skipped the text's leading whitespace only when the pattern
 * started with a space (the test was inverted), and `^exact$` compared
 * the trimmed text length with 0 instead of the pattern length, so it
 * never matched. Expected results are upstream telescope-fzf-native's. */

/* FzF's result as "score: positions" or "null", compared with the output
 * of upstream telescope-fzf-native (V1 algorithm) for the same input. */
bool check(u8 *text, u8 *pat, i32 case_mode, bool fuzzy, u8 *want) {
    u8 got[256], *p = str.dup(pat);
    i64 len;
    fzf.Position *pos = fzf.match(text, p, case_mode, fuzzy);
    if (!pos) {
        str.copy(got, "null");
    } else {
        len = libc.snprintf(got, sizeof(got), "%d:", pos.score);
        for (i64 i = 0; i < pos.size; i++) {
            len += libc.snprintf(got + len, sizeof(got) - len, " %d", pos.data[i]);
        }
        fzf.release(pos);
    }
    mem.free(p);
    if (str.cmp(got, want)) {
        printf("\n  FzF(\"%s\", \"%s\", %d, %d) = %s, want %s", text, pat, case_mode, fuzzy, got, want);
        return false;
    }
    return true;
}

i32 main() {
    printf("Test - fzf prefix suffix: ");
    i64 correct = 0, tests = 0;

    tests++; if (check("  abc", "^abc", 0, true, "80: 2 3 4")) { correct++; }
    tests++; if (check("abc", "^abc", 0, true, "80: 0 1 2")) { correct++; }
    tests++; if (check("  abc", "^ab", 0, true, "56: 2 3")) { correct++; }
    tests++; if (check("xabc", "^abc", 0, true, "null")) { correct++; }
    tests++; if (check("abc", "^abc$", 0, true, "80: 0 1 2")) { correct++; }
    tests++; if (check("  abc  ", "^abc$", 0, true, "80: 2 3 4")) { correct++; }
    tests++; if (check("abcd", "^abc$", 0, true, "null")) { correct++; }
    tests++; if (check("ab", "^abc$", 0, true, "null")) { correct++; }
    tests++; if (check("Fzf", "^Fzf$", 2, true, "80: 0 1 2")) { correct++; }
    tests++; if (check(" Fzf", "^fzf$", 0, true, "80: 1 2 3")) { correct++; }
    tests++; if (check(" Fzf", "^fzf$", 2, true, "null")) { correct++; }
    tests++; if (check("  HC_abc ", "^HC_", 0, true, "80: 2 3 4")) { correct++; }
    tests++; if (check("src/main.c", "^src", 0, true, "80: 0 1 2")) { correct++; }
    tests++; if (check("a", "^a$", 0, true, "32: 0")) { correct++; }
    tests++; if (check("abc", "!^abc$", 0, true, "null")) { correct++; }
    tests++; if (check("abc", "!^ab", 0, true, "null")) { correct++; }

    if (correct != tests) { printf("\n"); }
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
