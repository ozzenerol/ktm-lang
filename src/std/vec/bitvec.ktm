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

pub void BitVec.release() {
    if (!self) { return; }
    mem.free(self.entries);
    mem.free(self);
}

pub bool BitVec.set(u64 idx) {
    if (!self) { return false; }
    u64 blockIdx = idx / BIT_VEC_BITS;
    if (blockIdx >= self.blocks) {
        /* at least double, so setting bits in increasing order is linear */
        u64 blocks = blockIdx + 1;
        if (blocks < self.blocks * 2) { blocks = self.blocks * 2; }
        u32 *entries = mem.allocZero(sizeof(u32) * blocks);
        if (!entries) { return false; }
        mem.copy(entries, self.entries, sizeof(u32) * self.blocks);
        mem.free(self.entries);
        self.entries = entries;
        self.blocks = blocks;
    }
    self.entries[blockIdx] |= 1 << (idx & (BIT_VEC_BITS - 1));
    return true;
}

pub void BitVec.unset(u64 idx) {
    if (!self) { return; }
    u64 blockIdx = idx / BIT_VEC_BITS;
    if (blockIdx < self.blocks) {
        self.entries[blockIdx] &= ~(1 << (idx & (BIT_VEC_BITS - 1)));
    }
}

pub bool BitVec.test(u64 idx) {
    if (!self) { return false; }
    u64 blockIdx = idx / BIT_VEC_BITS;
    if (blockIdx < self.blocks) {
        return (self.entries[blockIdx] >> (idx & (BIT_VEC_BITS - 1))) & 1;
    }
    return false;
}

pub void BitVec.clear() {
    if (!self) { return; }
    mem.set(self.entries, 0, sizeof(u32) * self.blocks);
}
