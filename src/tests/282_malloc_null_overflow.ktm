import test;

import mem;

/* MAlloc wrote its size header through malloc's result without checking
 * it for null, so a failed allocation crashed, and size + 8 wrapped for
 * sizes near U64_MAX, so MAlloc(-1) returned a 7-byte block claiming
 * MSize -1. MAlloc, CAlloc and ReAlloc now return null instead; a failed
 * ReAlloc leaves the old block alone, like C's realloc. */

i32 main() {
    printf("Test - MAlloc null and overflow: ");
    i64 correct = 0, tests = 0, i, ok;
    u8 *p, *q;

    /* Sizes no machine can satisfy: fail straight away, nothing is
     * actually allocated. */
    tests++; if (mem.alloc(1 << 62) == null) { correct++; }
    tests++; if (mem.alloc(I64_MAX) == null) { correct++; }
    /* size + 8 wraps around to a tiny request */
    tests++; if (mem.alloc(-1) == null) { correct++; }
    tests++; if (mem.alloc(-8) == null) { correct++; }
    /* size + 8 == U64_MAX: no wrap, malloc itself says no */
    tests++; if (mem.alloc(-9) == null) { correct++; }
    tests++; if (mem.allocZero(-4) == null) { correct++; }
    tests++; if (mem.allocZero(1 << 62) == null) { correct++; }
    tests++; if (mem.realloc(null, -1) == null) { correct++; }

    /* A failed ReAlloc returns null and keeps the old block intact. */
    p = mem.alloc(32);
    for (i = 0; i < 32; i++) { p[i] = i; }
    q = mem.realloc(p, -1);
    ok = q == null && mem.size(p) == 32;
    for (i = 0; i < 32; i++) { if (p[i] != i) { ok = false; } }
    tests++; if (ok) { correct++; }
    q = mem.realloc(p, 1 << 62);
    ok = q == null && mem.size(p) == 32;
    for (i = 0; i < 32; i++) { if (p[i] != i) { ok = false; } }
    tests++; if (ok) { correct++; }
    mem.free(p);

    /* Ordinary allocations still work. */
    p = mem.alloc(24);
    tests++; if (p && mem.size(p) == 24) { correct++; }
    mem.free(p);
    p = mem.alloc(0);
    tests++; if (p && mem.size(p) == 0) { correct++; }
    mem.free(p);
    p = mem.allocZero(100);
    ok = p && mem.size(p) == 100;
    for (i = 0; i < 100; i++) { if (p[i]) { ok = false; } }
    tests++; if (ok) { correct++; }
    p = mem.realloc(p, 300);
    ok = p && mem.size(p) == 300;
    for (i = 0; i < 100; i++) { if (p[i]) { ok = false; } }
    tests++; if (ok) { correct++; }
    mem.free(p);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
