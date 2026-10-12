import math, test;

/* math.rotl/rotr/bswap/clz/ctz (v0.8.0) compile to one instruction where
 * they are called; through a function pointer the portable body runs.
 * Both must agree for every width, value and count (constant and
 * variable, 0, the width and past it), and popcount matches a bit loop. */

u64 gSeed = 0x9E3779B97F4A7C15;
u64 next() {
    gSeed = gSeed * 6364136223846793005 + 1442695040888963407;
    return gSeed ^ (gSeed >> 29);
}

i64 bits(u64 x) {
    i64 n = 0;
    while (x) { n += x & 1; x >>= 1; }
    return n;
}

i32 main() {
    printf("Test - bit intrinsics: ");
    i64 correct = 0, tests = 0;

    fn(u32, i64) u32 rotl32 = &math.rotl;
    fn(u64, i64) u64 rotl64 = &math.rotl;
    fn(u32, i64) u32 rotr32 = &math.rotr;
    fn(u64, i64) u64 rotr64 = &math.rotr;
    fn(u16) u16 bswap16 = &math.bswap;
    fn(u32) u32 bswap32 = &math.bswap;
    fn(u64) u64 bswap64 = &math.bswap;
    fn(u32) i64 clz32 = &math.clz;
    fn(u64) i64 clz64 = &math.clz;
    fn(u32) i64 ctz32 = &math.ctz;
    fn(u64) i64 ctz64 = &math.ctz;

    i64 bad = 0;
    for (i64 i = 0; i < 2000; i++) {
        u64 x = next();
        if (i % 7 == 0) { x >>= (i % 64); }   /* leading zeros too */
        if (i % 11 == 0) { x <<= (i % 64); }  /* and trailing ones */
        u32 y = (u32)x;
        u16 z = (u16)x;
        i64 n = (i64)(next() % 80);
        if (math.rotl(y, n) != rotl32(y, n) || math.rotr(y, n) != rotr32(y, n)) { bad++; }
        if (math.rotl(x, n) != rotl64(x, n) || math.rotr(x, n) != rotr64(x, n)) { bad++; }
        if (math.bswap(z) != bswap16(z) || math.bswap(y) != bswap32(y) ||
            math.bswap(x) != bswap64(x)) { bad++; }
        if (math.clz(y) != clz32(y) || math.clz(x) != clz64(x)) { bad++; }
        if (math.ctz(y) != ctz32(y) || math.ctz(x) != ctz64(x)) { bad++; }
        if (math.popcount(x) != bits(x) || math.popcount(y) != bits(y)) { bad++; }
    }
    tests++; if (bad == 0) { correct++; } else { printf("\n%d mismatches\n", bad); }

    u32 a = 0x80000001;
    u64 b = 0x8000000000000001;
    tests++; if (math.rotl(a, 1) == 3 && math.rotr(a, 1) == 0xC0000000 &&
                 math.rotl(a, 0) == a && math.rotr(a, 32) == a && math.rotl(a, 33) == 3) { correct++; }
    tests++; if (math.rotl(b, 4) == 0x18 && math.rotr(b, 4) == 0x1800000000000000 &&
                 math.rotr(b, 64) == b) { correct++; }
    tests++; if (math.bswap((u16)0x1234) == 0x3412 && math.bswap((u32)0x11223344) == 0x44332211 &&
                 math.bswap((u64)0x1122334455667788) == 0x8877665544332211) { correct++; }
    tests++; if (math.clz((u32)0) == 32 && math.clz((u64)0) == 64 && math.ctz((u32)0) == 32 &&
                 math.ctz((u64)0) == 64 && math.clz((u32)1) == 31 && math.ctz((u64)0x100000000) == 32) {
        correct++;
    }
    /* a value wider than the width: only its low bits count */
    u64 wide = 0xFFFFFFFF00000001;
    tests++; if (math.clz((u32)wide) == 31 && math.rotl((u32)wide, 4) == 0x10) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
