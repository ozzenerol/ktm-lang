module vec;
import mem;

const i64 BIT_VEC_BITS = 32;

pub struct BitVec {
    i64 blocks;
    u32 *entries;
};

pub BitVec *bitVecNew() {
    BitVec *bv = memAlloc(sizeof(BitVec));
    bv.entries = memAllocZero(sizeof(u32));
    bv.blocks = 1;
    return bv;
}

pub void bitVecRelease(BitVec *bv) {
    if (!bv) { return; }
    memFree(bv.entries);
    memFree(bv);
}

pub bool bitVecSet(BitVec *bv, u64 idx) {
    if (!bv) { return false; }
    u64 blockIdx = idx / BIT_VEC_BITS;
    if (blockIdx >= bv.blocks) {
        /* at least double, so setting bits in increasing order is linear */
        u64 blocks = blockIdx + 1;
        if (blocks < bv.blocks * 2) { blocks = bv.blocks * 2; }
        u32 *entries = memAllocZero(sizeof(u32) * blocks);
        if (!entries) { return false; }
        memCpy(entries, bv.entries, sizeof(u32) * bv.blocks);
        memFree(bv.entries);
        bv.entries = entries;
        bv.blocks = blocks;
    }
    bv.entries[blockIdx] |= 1 << (idx & (BIT_VEC_BITS - 1));
    return true;
}

pub void bitVecUnSet(BitVec *bv, u64 idx) {
    if (!bv) { return; }
    u64 blockIdx = idx / BIT_VEC_BITS;
    if (blockIdx < bv.blocks) {
        bv.entries[blockIdx] &= ~(1 << (idx & (BIT_VEC_BITS - 1)));
    }
}

pub bool bitVecTest(BitVec *bv, u64 idx) {
    if (!bv) { return false; }
    u64 blockIdx = idx / BIT_VEC_BITS;
    if (blockIdx < bv.blocks) {
        return (bv.entries[blockIdx] >> (idx & (BIT_VEC_BITS - 1))) & 1;
    }
    return false;
}

pub void bitVecClear(BitVec *bv) {
    if (!bv) { return; }
    memSet(bv.entries, 0, sizeof(u32) * bv.blocks);
}
