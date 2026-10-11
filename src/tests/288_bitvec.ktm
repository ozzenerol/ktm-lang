import test;

import mem, vec;

/* bitvec.ktm was not part of the library and every operation was broken:
 * BitVecSet grew to sizeof(u32) * block_idx + 1 bytes and left the new
 * last block uninitialised, BitVecUnSet cleared ~((1 << idx) & 31),
 * BitVecTest returned the whole word shifted by (idx & 31) & 1, and the
 * u32 block index wrapped for large indexes. */

const i64 MODEL_BITS = 5000;

i64 seed = 12345;
i64 rand() {
    seed = seed * 6364136223846793005 + 1442695040888963407;
    return (seed >> 33) & 0x7fffffff;
}

i32 main() {
    printf("Test - BitVec: ");
    i64 correct = 0, tests = 0, i, round, idx, wrong;
    u8 model[MODEL_BITS];
    vec.BitVec *bv = vec.bitNew();

    tests++; if (bv && bv.blocks >= 1 && !bv.test(0)) { correct++; }

    bv.set(3);
    tests++; if (bv.test(3) && !bv.test(2) && !bv.test(4)) { correct++; }
    bv.set(0);
    bv.unset(3);
    tests++; if (!bv.test(3) && bv.test(0) && bv.entries[0] == 1) { correct++; }
    bv.set(31);
    tests++; if (bv.test(31) && bv.entries[0] == 0x80000001) { correct++; }
    bv.unset(31);
    bv.unset(0);
    tests++; if (bv.entries[0] == 0) { correct++; }

    /* setting then clearing every bit of a word leaves it empty */
    for (i = 0; i < 32; i++) { bv.set(i); }
    tests++; if (bv.entries[0] == 0xFFFFFFFF) { correct++; }
    for (i = 0; i < 32; i++) { bv.unset(i); }
    tests++; if (bv.entries[0] == 0) { correct++; }

    /* growing: only the bit that was set reads back as set */
    tests++; if (bv.set(100)) { correct++; }
    wrong = 0;
    for (i = 0; i < 32 * bv.blocks + 64; i++) { if (bv.test(i) != (i == 100)) { wrong++; } }
    tests++; if (wrong == 0 && bv.blocks * 32 > 100) { correct++; }
    tests++; if (mem.size(bv.entries) >= bv.blocks * sizeof(u32)) { correct++; }

    /* large indexes don't wrap onto low bits (block 2^32 used to alias
     * block 0) and reading or clearing past the end allocates nothing */
    bv.set(5);
    idx = (1 << 37) + 5;
    i = bv.blocks;
    tests++; if (!bv.test(idx) && !bv.test(U64_MAX)) { correct++; }
    bv.unset(idx);
    bv.unset(U64_MAX);
    tests++; if (bv.test(5) && bv.blocks == i) { correct++; }

    bv.clear();
    wrong = 0;
    for (i = 0; i < 32 * bv.blocks; i++) { if (bv.test(i)) { wrong++; } }
    tests++; if (wrong == 0) { correct++; }
    bv.release();

    /* random operations against a byte-per-bit model */
    bv = vec.bitNew();
    mem.set(model, 0, MODEL_BITS);
    wrong = 0;
    for (round = 0; round < 20000; round++) {
        idx = rand() % MODEL_BITS;
        switch (rand() % 4) {
            case 0:
            case 1: bv.set(idx); model[idx] = 1; break;
            case 2: bv.unset(idx); model[idx] = 0; break;
            case 3: if (bv.test(idx) != model[idx]) { wrong++; } break;
        }
    }
    for (i = 0; i < MODEL_BITS + 100; i++) {
        if (i < MODEL_BITS) {
            if (bv.test(i) != model[i]) { wrong++; }
        } else if (bv.test(i)) { wrong++; }
    }
    tests++; if (wrong == 0) { correct++; }
    bv.release();

    vec.BitVec.release(null);
    tests++; if (!vec.BitVec.set(null, 1) && !vec.BitVec.test(null, 1)) { correct++; }
    vec.BitVec.unset(null, 1);
    vec.BitVec.clear(null);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
