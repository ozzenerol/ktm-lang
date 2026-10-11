import cstr, libc, map, mem, str, test;

/* cstr.appendFormat returned a brand new string and never freed the one passed
 * in, so the idiomatic `s = cstr.appendFormat(s, ...)` loop leaked every old s.
 * It now appends into the heap string it is given, growing it with
 * mem.realloc (which frees the old block) when it is full. The leak is
 * measured with libc's own count of bytes in use. */

/* test.heapInUse() is in the test module (src/tests/test) */

String valueStr(void *v) {
    return str.format("v%d", (i64)v);
}

i32 main() {
    printf("Test - CatPrint frees what it replaces: ");
    i64 tests = 9;
    i64 correct = 0;
    i64 i, before, delta;
    u8 *s, *t, want[1024];

    /* 1: the leaking loop; ~11MB used to stay allocated */
    before = test.heapInUse();
    s = cstr.dup("");
    for (i = 0; i < 2000; i++) { s = cstr.appendFormat(s, "%d", i); }
    mem.free(s);
    delta = test.heapInUse() - before;
    if (delta < 65536) { correct++; }
    else { printf("\n  CatPrint loop leaked %d bytes\n", delta); }

    /* 2: the result is still every piece in order */
    s = cstr.dup("id");
    i64 pos = 2;
    cstr.copy(want, "id");
    for (i = 0; i < 100; i++) {
        s = cstr.appendFormat(s, ",%d", i);
        pos += libc.snprintf(want + pos, 1024 - pos, ",%d", i);
    }
    if (!cstr.cmp(s, want) && cstr.len(s) == pos) { correct++; }
    mem.free(s);

    /* 3: a null dst starts a new string, as it did before */
    s = cstr.appendFormat(null, "x%d", 5);
    if (!cstr.cmp(s, "x5")) { correct++; }
    mem.free(s);

    /* 4: with room left, the text goes into the same block */
    s = mem.alloc(64);
    s[0] = '\0';
    t = cstr.appendFormat(s, "ab%d", 1);
    if (t == s && !cstr.cmp(t, "ab1")) { correct++; }
    mem.free(t);

    /* 5: a full MAlloc'd block grows */
    s = mem.alloc(4);
    cstr.copy(s, "abc");
    s = cstr.appendFormat(s, "%s", "defghij");
    if (!cstr.cmp(s, "abcdefghij") && mem.size(s) > 10) { correct++; }
    mem.free(s);

    /* 6: appending nothing leaves it alone */
    s = cstr.dup("same");
    s = cstr.appendFormat(s, "%s", "");
    if (!cstr.cmp(s, "same")) { correct++; }
    mem.free(s);

    /* 7: one append longer than STR_LEN, with padding */
    s = cstr.dup("[");
    s = cstr.appendFormat(s, "%300s]", "r");
    if (cstr.len(s) == 302 && s[0] == '[' && s[299] == ' ' &&
            s[300] == 'r' && s[301] == ']') { correct++; }
    mem.free(s);

    /* 8, 9: the map printers (Strings now); no double free, no leak */
    map.IntMap *im = map.intNew();
    for (i = 0; i < 50; i++) { im.set(i, (void *)i); }
    String ks = im.keysToStr();
    String vs = im.toStr(&valueStr);
    if (ks.startsWith("{0, 1, ") && ks.endsWith(", 49}") && cstr.count(ks.cstr(), ',') == 49 &&
            vs.startsWith("{[0] => v0, ") && vs.endsWith("[49] => v49}") &&
            cstr.count(vs.cstr(), ',') == 49) {
        correct++;
    }
    ks.free();
    vs.free();
    before = test.heapInUse();
    for (i = 0; i < 200; i++) {
        vs = im.toStr(&valueStr);
        vs.free();
    }
    delta = test.heapInUse() - before;
    if (delta < 65536) { correct++; }
    else { printf("\n  IntMap.toStr leaked %d bytes\n", delta); }
    im.release();

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
