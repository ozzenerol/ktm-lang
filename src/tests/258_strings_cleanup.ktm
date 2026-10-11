import test;

import libc, mem, str;

/* strings.ktm cleanup: strlen was declared twice (once as i64, once as
 * u64), strncasecmp and atol were public in the library but missing from
 * std.ktmh, and dead code went: MPrintQ's '$$' branch (a byte never equals
 * the two-byte constant), U64ToStr's rem > 9 branch and the PRTF_/CH_
 * constants nothing used. Behaviour is unchanged; this checks that the
 * declarations are usable and the touched functions still work. */

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - strings cleanup: ");

    /* from std.ktmh, with no declaration here. (The i32 result goes through
     * a variable: comparing an extern "c" i32 call directly with < 0 is a
     * separate, reported codegen bug.) */
    i32 eq = libc.strncasecmp("HeLLo", "hello", 5), lt = libc.strncasecmp("a", "B", 1);
    tests++;
    if (eq == 0 && lt < 0 && libc.atol("  -42x") == -42 && libc.strlen("four") == 4) { correct++; }

    /* U64ToStr writes the digits least significant first (internal helper) */
    u8 buf[32];
    i64 n = U64ToStr(buf, 12345);
    i64 z = U64ToStr(buf + 16, 0);
    tests++; if (n == 5 && !str.cmp(buf, "54321") && z == 1 && !str.cmp(buf + 16, "0")) { correct++; }

    /* MPrintQ: '$' is an ordinary character */
    u8 *q = str.quote("a$$b\n\"c\"");
    tests++; if (!str.cmp(q, "a$$b\\n\\\"c\\\"")) { correct++; }
    else { printf("MPrintQ -> %s\n", q); }
    mem.free(q);

    /* the removed constants are gone, the used ones stay */
    bool gone = true;
#ifdef CH_ESC
    gone = false;
#endif
#ifdef PRTF_NEG_E
    gone = false;
#endif
    tests++; if (gone && CH_SPACE == ' ' && PRTF_PAD_ZERO == 1) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
