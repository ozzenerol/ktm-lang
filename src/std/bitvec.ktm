#define BIT_VEC_BITS 32

class BitVec
{
  I64 blocks;
  U32 *entries;
};

BitVec *BitVecNew()
{
  BitVec *bv = MAlloc(sizeof(BitVec));
  bv->entries = CAlloc(sizeof(U32));
  bv->blocks = 1;
  return bv;
}

U0 BitVecRelease(BitVec *bv)
{
  if (!bv) return;
  Free(bv->entries);
  Free(bv);
}

Bool BitVecSet(BitVec *bv, U64 idx)
{
  if (!bv) return FALSE;
  U64 block_idx = idx / BIT_VEC_BITS;
  if (block_idx >= bv->blocks) {
    /* at least double, so setting bits in increasing order is linear */
    U64 blocks = block_idx + 1;
    if (blocks < bv->blocks * 2) blocks = bv->blocks * 2;
    U32 *entries = CAlloc(sizeof(U32) * blocks);
    if (!entries) return FALSE;
    MemCpy(entries, bv->entries, sizeof(U32) * bv->blocks);
    Free(bv->entries);
    bv->entries = entries;
    bv->blocks = blocks;
  }
  bv->entries[block_idx] |= 1 << (idx & (BIT_VEC_BITS - 1));
  return TRUE;
}

U0 BitVecUnSet(BitVec *bv, U64 idx)
{
  if (!bv) return;
  U64 block_idx = idx / BIT_VEC_BITS;
  if (block_idx < bv->blocks) {
    bv->entries[block_idx] &= ~(1 << (idx & (BIT_VEC_BITS - 1)));
  }
}

Bool BitVecTest(BitVec *bv, U64 idx)
{
  if (!bv) return FALSE;
  U64 block_idx = idx / BIT_VEC_BITS;
  if (block_idx < bv->blocks) {
    return (bv->entries[block_idx] >> (idx & (BIT_VEC_BITS - 1))) & 1;
  }
  return FALSE;
}

U0 BitVecClear(BitVec *bv)
{
  if (!bv) return;
  MemSet(bv->entries, 0, sizeof(U32) * bv->blocks);
}
