import libc, map, mem, str, test;

/* str.appendFormat returned a brand new string and never freed the one passed
 * in, so the idiomatic `s = str.appendFormat(s, ...)` loop leaked every old s.
 * It now appends into the heap string it is given, growing it with
 * mem.realloc (which frees the old block) when it is full. The leak is
 * measured with libc's own count of bytes in use. */

/* test.heapInUse() is in the test module (src/tests/test) */

u8 *valueStr(void *v) {
    return str.formatTo(null, "v%d", (i64)v);
}

i32 main() {
    printf("Test - CatPrint frees what it replaces: ");
    i64 tests = 9;
    i64 correct = 0;
    i64 i, before, delta;
    u8 *s, *t, want[1024];

    /* 1: the leaking loop; ~11MB used to stay allocated */
    before = test.heapInUse();
    s = str.dup("");
    for (i = 0; i < 2000; i++) { s = str.appendFormat(s, "%d", i); }
    mem.free(s);
    delta = test.heapInUse() - before;
    if (delta < 65536) { correct++; }
    else { printf("\n  CatPrint loop leaked %d bytes\n", delta); }

    /* 2: the result is still every piece in order */
    s = str.dup("id");
    i64 pos = 2;
    str.copy(want, "id");
    for (i = 0; i < 100; i++) {
        s = str.appendFormat(s, ",%d", i);
        pos += libc.snprintf(want + pos, 1024 - pos, ",%d", i);
    }
    if (!str.cmp(s, want) && str.len(s) == pos) { correct++; }
    mem.free(s);

    /* 3: a null dst starts a new string, as it did before */
    s = str.appendFormat(null, "x%d", 5);
    if (!str.cmp(s, "x5")) { correct++; }
    mem.free(s);

    /* 4: with room left, the text goes into the same block */
    s = mem.alloc(64);
    s[0] = '\0';
    t = str.appendFormat(s, "ab%d", 1);
    if (t == s && !str.cmp(t, "ab1")) { correct++; }
    mem.free(t);

    /* 5: a full MAlloc'd block grows */
    s = mem.alloc(4);
    str.copy(s, "abc");
    s = str.appendFormat(s, "%s", "defghij");
    if (!str.cmp(s, "abcdefghij") && mem.size(s) > 10) { correct++; }
    mem.free(s);

    /* 6: appending nothing leaves it alone */
    s = str.dup("same");
    s = str.appendFormat(s, "%s", "");
    if (!str.cmp(s, "same")) { correct++; }
    mem.free(s);

    /* 7: one append longer than STR_LEN, with padding */
    s = str.dup("[");
    s = str.appendFormat(s, "%300s]", "r");
    if (str.len(s) == 302 && s[0] == '[' && s[299] == ' ' &&
            s[300] == 'r' && s[301] == ']') { correct++; }
    mem.free(s);

    /* 8, 9: the map printers use str.appendFormat; no double free, no leak */
    map.IntMap *im = map.intNew();
    for (i = 0; i < 50; i++) { im.set(i, (void *)i); }
    s = im.keysToStr();
    t = im.toStr(&valueStr);
    if (s[0] == '{' && s[str.len(s) - 1] == '}' && str.count(s, ',') == 49 &&
            t[0] == '{' && t[str.len(t) - 1] == '}' && str.count(t, ',') == 49) {
        correct++;
    }
    mem.free(s);
    mem.free(t);
    before = test.heapInUse();
    for (i = 0; i < 200; i++) {
        s = im.toStr(&valueStr);
        mem.free(s);
    }
    delta = test.heapInUse() - before;
    if (delta < 65536) { correct++; }
    else { printf("\n  IntMapToString leaked %d bytes\n", delta); }
    im.release();

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
