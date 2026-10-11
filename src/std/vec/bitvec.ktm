module vec;
import mem;

const i64 BIT_VEC_BITS = 32;

pub struct BitVec {
    i64 blocks;
    u32 *entries;
};

pub BitVec *bitNew() {
    BitVec *bv = mem.alloc(sizeof(BitVec));
    bv.entries = mem.allocZero(sizeof(u32));
    bv.blocks = 1;
    return bv;
}

pub void bitRelease(BitVec *bv) {
    if (!bv) { return; }
    mem.free(bv.entries);
    mem.free(bv);
}

pub bool bitSet(BitVec *bv, u64 idx) {
    if (!bv) { return false; }
    u64 blockIdx = idx / BIT_VEC_BITS;
    if (blockIdx >= bv.blocks) {
        /* at least double, so setting bits in increasing order is linear */
        u64 blocks = blockIdx + 1;
        if (blocks < bv.blocks * 2) { blocks = bv.blocks * 2; }
        u32 *entries = mem.allocZero(sizeof(u32) * blocks);
        if (!entries) { return false; }
        mem.copy(entries, bv.entries, sizeof(u32) * bv.blocks);
        mem.free(bv.entries);
        bv.entries = entries;
        bv.blocks = blocks;
    }
    bv.entries[blockIdx] |= 1 << (idx & (BIT_VEC_BITS - 1));
    return true;
}

pub void bitUnset(BitVec *bv, u64 idx) {
    if (!bv) { return; }
    u64 blockIdx = idx / BIT_VEC_BITS;
    if (blockIdx < bv.blocks) {
        bv.entries[blockIdx] &= ~(1 << (idx & (BIT_VEC_BITS - 1)));
    }
}

pub bool bitTest(BitVec *bv, u64 idx) {
    if (!bv) { return false; }
    u64 blockIdx = idx / BIT_VEC_BITS;
    if (blockIdx < bv.blocks) {
        return (bv.entries[blockIdx] >> (idx & (BIT_VEC_BITS - 1))) & 1;
    }
    return false;
}

pub void bitClear(BitVec *bv) {
    if (!bv) { return; }
    mem.set(bv.entries, 0, sizeof(u32) * bv.blocks);
}
