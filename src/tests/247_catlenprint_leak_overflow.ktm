import test;

import json, mem, str;

/* CatLenPrint never freed the string it formats into, so every call
 * leaked a block (JsonToString, built on it, leaked one per piece), and
 * CatLenPrint(null, ...) allocated no room for the '\0' it then wrote. */

/* Bytes in use on the heap (test.heapInUse() is in the test module (src/tests/test)) */
i64 inUse() {
    return test.heapInUse();
}

i32 main() {
    printf("Test - CatLenPrint leak and overflow: ");
    i64 correct = 0, tests = 0, len, i, before;
    u8 *s;

    /* No leak: 20000 appends only grow the one string, so once it is
     * freed the heap is back where it was (each leaked temporary was 256+
     * bytes, 5 MB in all). The string is built once first so the
     * allocator's own bookkeeping is in place, and some slack is allowed:
     * macOS's allocator keeps ~100 KB of the freed blocks counted. */
    tests++;
    i64 pass, grew = 0;
    for (pass = 0; pass < 2; pass++) {
        before = inUse();
        len = 0;
        s = mem.alloc(16);
        s[0] = '\0';
        for (i = 0; i < 20000; i++) {
            s = str.appendFormatLen(s, &len, "%d,", i % 10);
        }
        mem.free(s);
        grew = inUse() - before;
    }
    if (grew < 512 * 1024 && len == 40000) { correct++; }
    else { printf("heap grew %d (len %d)\n", grew, len); }

    /* The text is right */
    tests++;
    len = 0;
    s = str.appendFormatLen(null, &len, "hello");
    if (len == 5 && !str.cmp(s, "hello") && mem.size(s) >= len + 1) { correct++; }
    else { printf("len %d MSize %d\n", len, mem.size(s)); }
    s = str.appendFormatLen(s, &len, " %s %d", "world", 42);
    tests++;
    if (len == 14 && !str.cmp(s, "hello world 42") && mem.size(s) >= len + 1) { correct++; }
    mem.free(s);

    /* From null, every length lands with room for the '\0' */
    tests++;
    bool ok = true;
    for (i = 0; i < 300; i++) {
        len = 0;
        s = str.appendFormatLen(null, &len, "%*s", i, "");
        if (len != i || mem.size(s) < i + 1 || s[i] != '\0') { ok = false; }
        mem.free(s);
    }
    if (ok) { correct++; }

    /* JsonToString no longer leaks */
    tests++;
    json.Value *j = json.parse("{\"a\":[1,2,3,true,null],\"b\":\"x\",\"c\":{\"d\":1.5}}");
    s = json.toStr(j);
    mem.free(s);
    before = inUse();
    for (i = 0; i < 2000; i++) {
        s = json.toStr(j);
        mem.free(s);
    }
    if (inUse() - before < 4096) { correct++; }
    else { printf("JsonToString heap grew %d\n", inUse() - before); }
    json.release(j);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
