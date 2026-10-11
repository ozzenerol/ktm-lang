#include "testhelper.ktm"

import libc, map;

/* IntMap hashed a key as `key & mask`, so keys sharing their low bits
 * (`i << 20`, multiples of 1024, aligned pointers) all went to the same
 * few slots and every insert and lookup walked one long probe chain:
 * 40000 keys `i << 20` took about 2 s. Keys are now mixed before masking.
 * Checks the clustering directly (deterministic) and a generous time
 * bound, plus edge keys and lookups of missing keys. */

i64 nowUs() {
    libc.timeval tv;
    libc.gettimeofday(&tv, null);
    return tv.tv_sec * 1000000 + tv.tv_usec;
}

i64 gErrors = 0;

/* The longest run of occupied slots: every probe for a key in the run
 * walks it */
i64 maxProbe(map.IntMap *m) {
    i64 run = 0, worst = 0;
    for (i64 s = 0; s < m.capacity; s++) {
        if (m.entries[s]) {
            run++;
            if (run > worst) { worst = run; }
        } else {
            run = 0;
        }
    }
    return worst;
}

/* Insert, find and delete `n` keys `i * mul` (or `i << shift`) */
i64 Run(i64 n, i64 shift, i64 mul) {
    map.IntMap *m = map.intNew();
    for (i64 i = 0; i < n; i++) { m.set((i * mul) << shift, (void *)i); }
    for (i64 i = 0; i < n; i++) {
        if ((i64)m.get((i * mul) << shift) != i) { gErrors++; }
        if (m.has(((i * mul) << shift) + 1) && mul * (1 << shift) > 1) { gErrors++; }
    }
    i64 worst = maxProbe(m);
    for (i64 i = 0; i < n; i += 2) {
        if (!m.delete((i * mul) << shift)) { gErrors++; }
    }
    if (m.size != n / 2) { gErrors++; }
    m.release();
    return worst;
}

i32 main() {
    printf("Test - IntMap hash mixing: ");
    i64 tests = 0;
    i64 correct = 0;
    i64 t0 = nowUs();
    i64 w12 = Run(40000, 12, 1);
    i64 w20 = Run(40000, 20, 1);
    i64 w32 = Run(40000, 32, 1);
    i64 w1k = Run(40000, 0, 1024);
    i64 wneg = Run(40000, 16, -1);
    i64 wseq = Run(40000, 0, 1);
    i64 ms = (nowUs() - t0) / 1000;

    tests++;
    if (gErrors == 0) { correct++; }
    else { printf("Failed - %d wrong results\n", gErrors); }

    tests++;
    if (w12 < 100 && w20 < 100 && w32 < 100 && w1k < 100 && wneg < 100 &&
            wseq < 100) {
        correct++;
            } else { printf("Failed - longest runs %d %d %d %d %d %d\n",
                 w12, w20, w32, w1k, wneg, wseq);
    }

    /* Generous: about 30 ms on a laptop, seconds before */
    tests++;
    if (ms < 1500) { correct++; }
    else { printf("Failed - took %d ms\n", ms); }

    /* Edge keys still work */
    map.IntMap *m = map.intNew(8);
    i64 edge[6] = {0, -1, 1, I64_MIN, I64_MAX, 1 << 63 >> 1};
    for (i64 i = 0; i < 6; i++) { m.set(edge[i], (void *)(i + 1)); }
    i64 bad = 0;
    for (i64 i = 0; i < 6; i++) { if ((i64)m.get(edge[i]) != i + 1) { bad++; } }
    tests++;
    if (bad == 0 && m.size == 6) { correct++; }
    else { printf("Failed - edge keys\n"); }
    m.release();

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
