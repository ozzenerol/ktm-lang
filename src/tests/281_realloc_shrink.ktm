#include "testhelper.ktm"

import mem;

/* ReAlloc copied MSize(old) bytes into the new block even when the new
 * size was smaller, writing past the end of the new allocation. */

i32 main() {
    printf("Test - ReAlloc shrink: ");
    i64 correct = 0, tests = 0, i, round, ok;
    u8 *p, *q[64];

    /* Shrinking a big block: the old code copied 4096 bytes into a
     * 16-byte block and trashed the heap around it. */
    for (round = 0; round < 64; round++) {
        p = mem.alloc(4096);
        for (i = 0; i < 4096; i++) { p[i] = i & 0xFF; }
        q[round] = mem.realloc(p, 16);
    }
    ok = true;
    for (round = 0; round < 64; round++) {
        if (mem.size(q[round]) != 16) { ok = false; }
        for (i = 0; i < 16; i++) { if (q[round][i] != i) { ok = false; } }
    }
    for (round = 0; round < 64; round++) { mem.free(q[round]); }
    tests++; if (ok) { correct++; }

    p = mem.alloc(100);
    for (i = 0; i < 100; i++) { p[i] = i; }
    p = mem.realloc(p, 4);
    tests++; if (mem.size(p) == 4 && p[0] == 0 && p[3] == 3) { correct++; }

    /* Growing still copies all of the old contents. */
    p = mem.realloc(p, 200);
    tests++; if (mem.size(p) == 200 && p[0] == 0 && p[3] == 3) { correct++; }

    /* Same size. */
    for (i = 0; i < 200; i++) { p[i] = 200 - i; }
    p = mem.realloc(p, 200);
    ok = mem.size(p) == 200;
    for (i = 0; i < 200; i++) { if (p[i] != 200 - i) { ok = false; } }
    tests++; if (ok) { correct++; }

    /* Down to zero bytes. */
    p = mem.realloc(p, 0);
    tests++; if (p && mem.size(p) == 0) { correct++; }
    mem.free(p);

    /* null acts like MAlloc. */
    p = mem.realloc(null, 7);
    tests++; if (p && mem.size(p) == 7) { correct++; }
    mem.free(p);

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
