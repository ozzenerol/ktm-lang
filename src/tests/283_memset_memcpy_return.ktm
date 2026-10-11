import mem, test;

/* mem.set and mem.copy are declared to return void * but returned junk: the
 * x86_64 mem.set copied RAX into RDI instead of the other way round,
 * mem.copy never set RAX, and on arm64 mem.copy returned dst + len and
 * mem.set with len 0 returned an uninitialised register. Both return
 * dst now, like C's memset/memcpy. */

i32 main() {
    printf("Test - MemSet/MemCpy return dst: ");
    i64 correct = 0, tests = 0, i, ok;
    u8 buf[64], src[64];

    for (i = 0; i < 64; i++) { buf[i] = 0; src[i] = 'a' + i % 26; }

    tests++; if (mem.set(buf, 'x', 4) == buf) { correct++; }
    tests++; if (mem.set(buf, 'y', 0) == buf) { correct++; }
    tests++; if (mem.set(buf + 3, 0, 1) == buf + 3) { correct++; }
    tests++; if (mem.copy(buf, src, 5) == buf) { correct++; }
    tests++; if (mem.copy(buf, src, 0) == buf) { correct++; }
    tests++; if (mem.copy(buf + 10, src, 64 - 10) == buf + 10) { correct++; }

    /* The copies and fills themselves still work. */
    mem.set(buf, 0, 64);
    ok = mem.copy(buf + 1, src, 3) == buf + 1;
    ok = ok && buf[0] == 0 && buf[1] == 'a' && buf[3] == 'c' && buf[4] == 0;
    tests++; if (ok) { correct++; }
    ok = mem.set(buf, 'z', 2) == buf && buf[0] == 'z' && buf[1] == 'z' && buf[2] == 'b';
    tests++; if (ok) { correct++; }

    /* Chaining on the result. */
    u8 *p = mem.set(mem.alloc(16), 7, 16);
    ok = p != null && mem.size(p) == 16;
    for (i = 0; ok && i < 16; i++) { if (p[i] != 7) { ok = false; } }
    tests++; if (ok) { correct++; }
    u8 *q = mem.copy(mem.alloc(16), p, 16);
    ok = q != null && q != p && mem.size(q) == 16;
    for (i = 0; ok && i < 16; i++) { if (q[i] != 7) { ok = false; } }
    tests++; if (ok) { correct++; }
    mem.free(p);
    mem.free(q);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
