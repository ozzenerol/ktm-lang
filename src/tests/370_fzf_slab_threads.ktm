import fzf, libc, test;

/* FzF allocated (CAlloc) and freed a 200 KB slab on every call, which no
 * matcher ever used: slow, and on macOS the freed blocks stayed counted as
 * in use. The heap's high-water mark must not move when FzF is called, a
 * loop of calls must leave the heap where it was (on macOS too). And the
 * pattern was split with strtok, whose position is one global shared by
 * all threads: FzF from several threads at once gave wrong answers. */

#ifdef IS_MACOS
i64 HeapPeak() {
    CHeapZoneStats st;
    malloc_zone_statistics(null, &st);
    return st.max_size_in_use;
}
#else
extern "c" i32 mallopt(i32 param, i32 value);
extern "c" i32 malloc_trim(u64 pad);
/* glibc: no trimming, no top padding and no mmap for these sizes, so the
 * main arena only grows, by about what the largest live block needs:
 * HeapPeak() trims it first and reports its size, and calling it again
 * after some work tells how far the heap reached in between. */
void heapPeakInit() {
    mallopt(-1, 1 << 30); /* M_TRIM_THRESHOLD */
    mallopt(-2, 0);       /* M_TOP_PAD */
    mallopt(-3, 16 << 20); /* M_MMAP_THRESHOLD */
}
i64 heapPeak() {
    malloc_trim(0); /* the arena starts without free space at its top */
    CHeapMallInfo2 m = mallinfo2();
    return m.arena + m.hblkhd;
}
#endif

u8 *text = "src/stdlib/fzf.ktm";
u8 *pats[4];
i64 wantScore[4];
i64 wantSize[4];

i64 checkAll() {
    i64 ok = 0, i;
    for (i = 0; i < 4; i++) {
        fzf.Position *pos = fzf.match(text, pats[i]);
        if (pos && pos.score == wantScore[i] && pos.size == wantSize[i]) { ok++; }
        fzf.release(pos);
    }
    return ok;
}

void *worker(void *arg) {
    i64 *bad = arg, i;
    for (i = 0; i < 2000; i++) {
        if (checkAll() != 4) { *bad = *bad + 1; }
    }
    return null;
}

i32 main() {
    printf("Test - fzf slab and threads: ");
    i64 correct = 0, tests = 0, i, before, grew;
    fzf.Position *pos;
#ifndef IS_MACOS
    heapPeakInit();
#endif

    pats[0] = "fzf"; pats[1] = "^src"; pats[2] = "ktm$"; pats[3] = "lib fz";

    /* the first calls: the peak grows by at most the small blocks a match
     * needs, not by 200 KB (only telling in AOT: under -jit this runs in
     * the compiler's process, whose heap has free holes the slab fits in) */
    before = heapPeak();
    for (i = 0; i < 4; i++) {
        pos = fzf.match(text, pats[i]);
        if (pos) {
            wantScore[i] = pos.score;
            wantSize[i] = pos.size;
        }
        fzf.release(pos);
    }
    grew = heapPeak() - before;
    tests++; if (grew < 64 * 1024) { correct++; } else { printf("heap peak grew %d bytes ", grew); }

    tests++; if (checkAll() == 4) { correct++; } else { printf("results differ "); }

    /* many calls leave the heap where it was, macOS included */
    for (i = 0; i < 50; i++) { checkAll(); }
    before = test.heapInUse();
    for (i = 0; i < 2000; i++) { checkAll(); }
    grew = test.heapInUse() - before;
    tests++; if (grew < 4096) { correct++; } else { printf("heap grew %d bytes ", grew); }

    /* an empty pattern returns null and leaks nothing */
    before = test.heapInUse();
    for (i = 0; i < 2000; i++) {
        pos = fzf.match(text, "");
        if (pos) { fzf.release(pos); grew = -1; }
    }
    tests++;
    if (grew != -1 && test.heapInUse() - before < 4096) { correct++; }
    else { printf("empty pattern leaked "); }

    /* the same answers from four threads at once */
    libc.pthread_t *th[4];
    i64 bad[4];
    for (i = 0; i < 4; i++) {
        bad[i] = 0;
        libc.pthread_create(&th[i], null, &worker, &bad[i]);
    }
    for (i = 0; i < 4; i++) { libc.pthread_join(th[i]); }
    tests++;
    if (bad[0] + bad[1] + bad[2] + bad[3] == 0) { correct++; }
    else { printf("threads got wrong results "); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
