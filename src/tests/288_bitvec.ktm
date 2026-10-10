#include "testhelper.ktm"

/* bitvec.ktm was not part of the library and every operation was broken:
 * BitVecSet grew to sizeof(U32) * block_idx + 1 bytes and left the new
 * last block uninitialised, BitVecUnSet cleared ~((1 << idx) & 31),
 * BitVecTest returned the whole word shifted by (idx & 31) & 1, and the
 * U32 block index wrapped for large indexes. */

#define MODEL_BITS 5000

I64 seed = 12345;
I64 Rand()
{
  seed = seed * 6364136223846793005 + 1442695040888963407;
  return (seed >> 33) & 0x7fffffff;
}

I32 Main()
{
  "Test - BitVec: ";
  I64 correct = 0, tests = 0, i, round, idx, wrong;
  U8 model[MODEL_BITS];
  BitVec *bv = BitVecNew();

  tests++; if (bv && bv->blocks >= 1 && !BitVecTest(bv, 0)) correct++;

  BitVecSet(bv, 3);
  tests++; if (BitVecTest(bv, 3) && !BitVecTest(bv, 2) && !BitVecTest(bv, 4)) correct++;
  BitVecSet(bv, 0);
  BitVecUnSet(bv, 3);
  tests++; if (!BitVecTest(bv, 3) && BitVecTest(bv, 0) && bv->entries[0] == 1) correct++;
  BitVecSet(bv, 31);
  tests++; if (BitVecTest(bv, 31) && bv->entries[0] == 0x80000001) correct++;
  BitVecUnSet(bv, 31);
  BitVecUnSet(bv, 0);
  tests++; if (bv->entries[0] == 0) correct++;

  /* setting then clearing every bit of a word leaves it empty */
  for (i = 0; i < 32; i++) BitVecSet(bv, i);
  tests++; if (bv->entries[0] == 0xFFFFFFFF) correct++;
  for (i = 0; i < 32; i++) BitVecUnSet(bv, i);
  tests++; if (bv->entries[0] == 0) correct++;

  /* growing: only the bit that was set reads back as set */
  tests++; if (BitVecSet(bv, 100)) correct++;
  wrong = 0;
  for (i = 0; i < 32 * bv->blocks + 64; i++) if (BitVecTest(bv, i) != (i == 100)) wrong++;
  tests++; if (wrong == 0 && bv->blocks * 32 > 100) correct++;
  tests++; if (MSize(bv->entries) >= bv->blocks * sizeof(U32)) correct++;

  /* large indexes don't wrap onto low bits (block 2^32 used to alias
   * block 0) and reading or clearing past the end allocates nothing */
  BitVecSet(bv, 5);
  idx = (1 << 37) + 5;
  i = bv->blocks;
  tests++; if (!BitVecTest(bv, idx) && !BitVecTest(bv, U64_MAX)) correct++;
  BitVecUnSet(bv, idx);
  BitVecUnSet(bv, U64_MAX);
  tests++; if (BitVecTest(bv, 5) && bv->blocks == i) correct++;

  BitVecClear(bv);
  wrong = 0;
  for (i = 0; i < 32 * bv->blocks; i++) if (BitVecTest(bv, i)) wrong++;
  tests++; if (wrong == 0) correct++;
  BitVecRelease(bv);

  /* random operations against a byte-per-bit model */
  bv = BitVecNew();
  MemSet(model, 0, MODEL_BITS);
  wrong = 0;
  for (round = 0; round < 20000; round++) {
    idx = Rand() % MODEL_BITS;
    switch (Rand() % 4) {
      case 0:
      case 1: BitVecSet(bv, idx); model[idx] = 1; break;
      case 2: BitVecUnSet(bv, idx); model[idx] = 0; break;
      case 3: if (BitVecTest(bv, idx) != model[idx]) wrong++; break;
    }
  }
  for (i = 0; i < MODEL_BITS + 100; i++) {
    if (i < MODEL_BITS) {
      if (BitVecTest(bv, i) != model[i]) wrong++;
    } else if (BitVecTest(bv, i)) wrong++;
  }
  tests++; if (wrong == 0) correct++;
  BitVecRelease(bv);

  BitVecRelease(NULL);
  tests++; if (!BitVecSet(NULL, 1) && !BitVecTest(NULL, 1)) correct++;
  BitVecUnSet(NULL, 1);
  BitVecClear(NULL);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
