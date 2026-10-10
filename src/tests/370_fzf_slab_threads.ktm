#include "testhelper.ktm"

/* FzF allocated (CAlloc) and freed a 200 KB slab on every call, which no
 * matcher ever used: slow, and on macOS the freed blocks stayed counted as
 * in use. The heap's high-water mark must not move when FzF is called, a
 * loop of calls must leave the heap where it was (on macOS too). And the
 * pattern was split with strtok, whose position is one global shared by
 * all threads: FzF from several threads at once gave wrong answers. */

#ifdef IS_MACOS
I64 HeapPeak()
{
  CHeapZoneStats st;
  malloc_zone_statistics(NULL, &st);
  return st.max_size_in_use;
}
#else
extern "c" I32 mallopt(I32 param, I32 value);
extern "c" I32 malloc_trim(U64 pad);
/* glibc: no trimming, no top padding and no mmap for these sizes, so the
 * main arena only grows, by about what the largest live block needs:
 * HeapPeak() trims it first and reports its size, and calling it again
 * after some work tells how far the heap reached in between. */
U0 HeapPeakInit()
{
  mallopt(-1, 1 << 30); /* M_TRIM_THRESHOLD */
  mallopt(-2, 0);       /* M_TOP_PAD */
  mallopt(-3, 16 << 20); /* M_MMAP_THRESHOLD */
}
I64 HeapPeak()
{
  malloc_trim(0); /* the arena starts without free space at its top */
  CHeapMallInfo2 m = mallinfo2();
  return m.arena + m.hblkhd;
}
#endif

U8 *text = "src/stdlib/fzf.ktm";
U8 *pats[4];
I64 want_score[4];
I64 want_size[4];

I64 CheckAll()
{
  I64 ok = 0, i;
  for (i = 0; i < 4; i++) {
    FzFPosition *pos = FzF(text, pats[i]);
    if (pos && pos->score == want_score[i] && pos->size == want_size[i]) ok++;
    FzFPositionRelease(pos);
  }
  return ok;
}

U0 *Worker(U0 *arg)
{
  I64 *bad = arg, i;
  for (i = 0; i < 2000; i++) {
    if (CheckAll() != 4) *bad = *bad + 1;
  }
  return NULL;
}

I32 Main()
{
  "Test - fzf slab and threads: ";
  I64 correct = 0, tests = 0, i, before, grew;
  FzFPosition *pos;
#ifndef IS_MACOS
  HeapPeakInit();
#endif

  pats[0] = "fzf"; pats[1] = "^src"; pats[2] = "ktm$"; pats[3] = "lib fz";

  /* the first calls: the peak grows by at most the small blocks a match
   * needs, not by 200 KB (only telling in AOT: under -jit this runs in
   * the compiler's process, whose heap has free holes the slab fits in) */
  before = HeapPeak();
  for (i = 0; i < 4; i++) {
    pos = FzF(text, pats[i]);
    if (pos) {
      want_score[i] = pos->score;
      want_size[i] = pos->size;
    }
    FzFPositionRelease(pos);
  }
  grew = HeapPeak() - before;
  tests++; if (grew < 64 * 1024) correct++; else "heap peak grew %d bytes ", grew;

  tests++; if (CheckAll() == 4) correct++; else "results differ ";

  /* many calls leave the heap where it was, macOS included */
  for (i = 0; i < 50; i++) CheckAll();
  before = HeapInUse();
  for (i = 0; i < 2000; i++) CheckAll();
  grew = HeapInUse() - before;
  tests++; if (grew < 4096) correct++; else "heap grew %d bytes ", grew;

  /* an empty pattern returns NULL and leaks nothing */
  before = HeapInUse();
  for (i = 0; i < 2000; i++) {
    pos = FzF(text, "");
    if (pos) { FzFPositionRelease(pos); grew = -1; }
  }
  tests++;
  if (grew != -1 && HeapInUse() - before < 4096) correct++;
  else "empty pattern leaked ";

  /* the same answers from four threads at once */
  pthread_t *th[4];
  I64 bad[4];
  for (i = 0; i < 4; i++) {
    bad[i] = 0;
    pthread_create(&th[i], NULL, &Worker, &bad[i]);
  }
  for (i = 0; i < 4; i++) pthread_join(th[i]);
  tests++;
  if (bad[0] + bad[1] + bad[2] + bad[3] == 0) correct++;
  else "threads got wrong results ";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
