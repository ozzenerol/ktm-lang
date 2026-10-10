public U0 PrintResult(I64 correct, I64 total) {
  if (correct != total) "\033[0;31mFAILED: %d/%d\033[0;0m\n", correct, total;
  else "\033[0;32mPASSED: %d/%d\033[0;0m\n", correct, total;
}

/* Bytes the C heap has in use, to check that a loop leaks nothing:
 * malloc_zone_statistics on macOS, glibc's mallinfo2 (2.33+) elsewhere.
 * mallinfo2 returns its struct by value; declaring it as filling a
 * pointer argument only worked on x86_64, where the hidden return
 * pointer happens to be the first argument register. */
#ifdef IS_MACOS
class CHeapZoneStats
{
  U32 blocks_in_use;
  U32 pad;
  U64 size_in_use;
  U64 max_size_in_use;
  U64 size_allocated;
};
extern "c" U0 malloc_zone_statistics(U0 *zone, CHeapZoneStats *stats);
I64 HeapInUse()
{
  CHeapZoneStats st;
  malloc_zone_statistics(NULL, &st);
  return st.size_in_use;
}
#else
class CHeapMallInfo2
{
  U64 arena, ordblks, smblks, hblks, hblkhd;
  U64 usmblks, fsmblks, uordblks, fordblks, keepcost;
};
extern "c" CHeapMallInfo2 mallinfo2();
I64 HeapInUse()
{
  CHeapMallInfo2 m = mallinfo2();
  return m.uordblks + m.hblkhd;
}
#endif
