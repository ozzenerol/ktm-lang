import mem, str, test;

/* The formatter's character writer (SPutChar, now private to str) grew a
 * full buffer with MAlloc(i<<1+1), which ktm parses as i<<(1+1): from an
 * empty 1-byte buffer it allocated 0 bytes and wrote the char and its NUL
 * past the block. Checked through what uses it: appending one character
 * at a time to a growing string, and formatting into strings of every
 * small size (valgrind checks the AOT binary). */

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - formatted strings grow: ");

    /* from an empty string, one char at a time */
    u8 *s = str.dup("");
    bool ok = true;
    for (i64 i = 0; i < 1000; ++i) {
        s = str.appendFormat(s, "%c", 'a' + i % 26);
        if (str.len(s) != i + 1) { ok = false; }
    }
    for (i64 i = 0; i < 1000; ++i) { if (s[i] != 'a' + i % 26) { ok = false; } }
    tests++; if (ok && str.len(s) == 1000) { correct++; }
    mem.free(s);

    /* results of every small size */
    ok = true;
    for (i64 size = 0; size < 24; ++size) {
        u8 *r = str.format("%*s", size, "");
        if (str.len(r) != size) { ok = false; }
        mem.free(r);
    }
    tests++; if (ok) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
