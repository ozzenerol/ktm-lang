import cstr, fzf, mem, test;

/* Every fzf.match call leaked its parsed terms: releasing a term set freed the
 * terms but not their text, and in case-sensitive mode the term's copy of
 * the text was not even recorded (og_str stayed null). */

i64 runAll(u8 *text) {
    i64 hits = 0, i, j;
    u8 *pats[7];
    i32 modes[3];
    fzf.Position *pos;
    pats[0] = "abc"; pats[1] = "ABC"; pats[2] = "'ab !zz"; pats[3] = "^a c$ | b";
    pats[4] = "x"; pats[5] = "Ab cD"; pats[6] = "| Ab";
    modes[0] = fzf.CASE_SMART; modes[1] = fzf.CASE_IGNORE; modes[2] = fzf.CASE_RESPECT;
    for (i = 0; i < 7; i++) {
        for (j = 0; j < 3; j++) {
            u8 *p = cstr.dup(pats[i]);
            pos = fzf.match(text, p, modes[j]);
            if (pos) { hits++; }
            fzf.release(pos);
            mem.free(p);
        }
    }
    return hits;
}

i32 main() {
    printf("Test - fzf term leak: ");
    i64 correct = 0, tests = 0, i, before, delta, hits;

    hits = runAll("aBc abc ABC abcd");
    for (i = 0; i < 20; i++) { runAll("aBc abc ABC abcd"); }
    before = test.heapInUse();
    for (i = 0; i < 500; i++) {
        if (runAll("aBc abc ABC abcd") != hits) { hits = -1; }
    }
    delta = test.heapInUse() - before;
    tests++; if (hits > 0) { correct++; }
    tests++; if (delta < 4096) { correct++; } else { printf("leaked %d bytes ", delta); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
