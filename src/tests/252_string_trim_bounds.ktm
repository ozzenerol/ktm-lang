import str, test;

/* str.trimStart and str.trimEnd on an empty or all-blank string stay
 * inside it (the old u8 * trimEnd walked back past the start and wrote
 * before it). They are views now: the result is part of the input,
 * nothing written, and a sub-slice is trimmed within its own bounds. An
 * empty slice (null, 0) is fine. */

bool trimEnd(u8[] in, u8[] want) {
    u8[] r = str.trimEnd(in);
    bool ok = r.equals(want) && (!in.len || r.ptr == in.ptr) && r.len <= in.len;
    if (!ok) { printf("trimEnd(\"%s\") -> \"%s\"\n", in, r); }
    return ok;
}

bool trimStart(u8[] in, u8[] want) {
    u8[] r = str.trimStart(in);
    bool ok = r.equals(want) && r.ptr + r.len == in.ptr + in.len;
    if (!ok) { printf("trimStart(\"%s\") -> \"%s\"\n", in, r); }
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - string trim bounds: ");

    bool ok = trimEnd("", "") && trimEnd("   ", "") && trimEnd(" \t\n", "") &&
              trimEnd("ab  ", "ab") && trimEnd("ab", "ab") && trimEnd("  ab", "  ab");
    tests++; if (ok) { correct++; }

    /* a sub-slice: only its bytes count */
    u8[] text = "ab   cd";
    tests++; if (trimEnd(text[:4], "ab") && trimEnd(text[:0], "")) { correct++; }

    /* the audit's repro: an all-blank part inside a buffer, nothing written */
    u8 big[6] = {'x', ' ', ' ', ' ', ' ', 0};
    u8[] blank = big[3:5];
    u8[] r = str.trimEnd(blank);
    tests++; if (r.len == 0 && r.ptr == &big[3] && big[2] == ' ' && big[0] == 'x') { correct++; }

    ok = trimStart("", "") && trimStart("   ", "") && trimStart("  ab", "ab") &&
         trimStart("ab", "ab") && trimStart("ab  ", "ab  ");
    tests++; if (ok) { correct++; }
    u8[] more = "   abc   def";
    tests++; if (trimStart(more[:6], "abc") && trimStart("      xyz"[:4], "")) { correct++; }

    u8[] none;
    tests++;
    if (str.trimStart(none).len == 0 && str.trimEnd(none).len == 0 && none.trim().len == 0) {
        correct++;
    }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
