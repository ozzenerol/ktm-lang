pub void PrintResult(i64 correct, i64 total) {
    if (correct != total) { printf("\033[0;31mFAILED: %d/%d\033[0;0m\n", correct, total); }
    else { printf("\033[0;32mPASSED: %d/%d\033[0;0m\n", correct, total); }
}

/* Bytes the C heap has in use, to check that a loop leaks nothing:
 * malloc_zone_statistics on macOS, glibc's mallinfo2 (2.33+) elsewhere.
 * mallinfo2 returns its struct by value; declaring it as filling a
 * pointer argument only worked on x86_64, where the hidden return
 * pointer happens to be the first argument register. */
#ifdef IS_MACOS
struct CHeapZoneStats {
    u32 blocks_in_use;
    u32 pad;
    u64 size_in_use;
    u64 max_size_in_use;
    u64 size_allocated;
};
extern "c" void malloc_zone_statistics(void *zone, CHeapZoneStats *stats);
i64 HeapInUse() {
    CHeapZoneStats st;
    malloc_zone_statistics(null, &st);
    return st.size_in_use;
}
#else
struct CHeapMallInfo2 {
    u64 arena, ordblks, smblks, hblks, hblkhd;
    u64 usmblks, fsmblks, uordblks, fordblks, keepcost;
};
extern "c" CHeapMallInfo2 mallinfo2();
i64 HeapInUse() {
    CHeapMallInfo2 m = mallinfo2();
    return m.uordblks + m.hblkhd;
}
#endif
