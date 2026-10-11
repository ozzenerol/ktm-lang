import test;

import mem;

/* By-value classes of every size from 1 to 16 bytes, and two with float
 * fields (12 bytes of f32; an f64 then 3 bytes), passed to and
 * returned from functions. AAPCS64 passes such a class in one or two x
 * registers; the arm64 backends (compiled and JIT) only moved 1/2/4/8
 * byte pieces and failed to compile a 3, 5, 6, 7, 9..15 byte one
 * ("3-byte struct param chunk not supported"). Covers parameters in
 * registers and on the stack (after 7 i64 arguments the 2-register
 * classes no longer fit), returns, and that the neighbours of the copies
 * are left alone. */

struct S1 { u8 b[1]; };
struct S2 { u8 b[2]; };
struct S3 { u8 b[3]; };
struct S4 { u8 b[4]; };
struct S5 { u8 b[5]; };
struct S6 { u8 b[6]; };
struct S7 { u8 b[7]; };
struct S8 { u8 b[8]; };
struct S9 { u8 b[9]; };
struct S10 { u8 b[10]; };
struct S11 { u8 b[11]; };
struct S12 { u8 b[12]; };
struct S13 { u8 b[13]; };
struct S14 { u8 b[14]; };
struct S15 { u8 b[15]; };
struct S16 { u8 b[16]; };

S1 mk1(i64 seed) {
    S1 s;
    i64 i;
    for (i = 0; i < 1; i++) { s.b[i] = seed + i * 3; }
    return s;
}

/* 0 when `s` holds what Mk1(seed) made */
i64 diff1(S1 s, i64 seed) {
    i64 i, bad = 0;
    for (i = 0; i < 1; i++) { if (s.b[i] != (seed + i * 3) & 0xFF) { bad++; } }
    return bad;
}

/* The same, with the class past the argument registers */
i64 late1(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, S1 s, i64 seed) {
    if (a + b + c + d + e + f + g != 28) { return 100; }
    return diff1(s, seed);
}

/* Returns a parameter straight back */
S1 echo1(S1 s) { return s; }

bool check1() {
    S1 arr[3];
    S1 s = mk1(10);
    i64 i, bad = 0;
    mem.set(arr, 0xEE, sizeof(arr));
    arr[1] = echo1(mk1(40));
    for (i = 0; i < 1; i++) {
        if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) { bad++; }
    }
    bad += diff1(arr[1], 40);
    bad += diff1(s, 10);
    bad += diff1(mk1(20), 20);
    bad += late1(1, 2, 3, 4, 5, 6, 7, s, 10);
    bad += late1(1, 2, 3, 4, 5, 6, 7, echo1(s), 10);
    if (bad) { printf("size 1: %d bad\n", bad); }
    return bad == 0;
}

S2 mk2(i64 seed) {
    S2 s;
    i64 i;
    for (i = 0; i < 2; i++) { s.b[i] = seed + i * 3; }
    return s;
}

/* 0 when `s` holds what Mk2(seed) made */
i64 diff2(S2 s, i64 seed) {
    i64 i, bad = 0;
    for (i = 0; i < 2; i++) { if (s.b[i] != (seed + i * 3) & 0xFF) { bad++; } }
    return bad;
}

/* The same, with the class past the argument registers */
i64 late2(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, S2 s, i64 seed) {
    if (a + b + c + d + e + f + g != 28) { return 100; }
    return diff2(s, seed);
}

/* Returns a parameter straight back */
S2 echo2(S2 s) { return s; }

bool check2() {
    S2 arr[3];
    S2 s = mk2(10);
    i64 i, bad = 0;
    mem.set(arr, 0xEE, sizeof(arr));
    arr[1] = echo2(mk2(40));
    for (i = 0; i < 2; i++) {
        if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) { bad++; }
    }
    bad += diff2(arr[1], 40);
    bad += diff2(s, 10);
    bad += diff2(mk2(20), 20);
    bad += late2(1, 2, 3, 4, 5, 6, 7, s, 10);
    bad += late2(1, 2, 3, 4, 5, 6, 7, echo2(s), 10);
    if (bad) { printf("size 2: %d bad\n", bad); }
    return bad == 0;
}

S3 mk3(i64 seed) {
    S3 s;
    i64 i;
    for (i = 0; i < 3; i++) { s.b[i] = seed + i * 3; }
    return s;
}

/* 0 when `s` holds what Mk3(seed) made */
i64 diff3(S3 s, i64 seed) {
    i64 i, bad = 0;
    for (i = 0; i < 3; i++) { if (s.b[i] != (seed + i * 3) & 0xFF) { bad++; } }
    return bad;
}

/* The same, with the class past the argument registers */
i64 late3(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, S3 s, i64 seed) {
    if (a + b + c + d + e + f + g != 28) { return 100; }
    return diff3(s, seed);
}

/* Returns a parameter straight back */
S3 echo3(S3 s) { return s; }

bool check3() {
    S3 arr[3];
    S3 s = mk3(10);
    i64 i, bad = 0;
    mem.set(arr, 0xEE, sizeof(arr));
    arr[1] = echo3(mk3(40));
    for (i = 0; i < 3; i++) {
        if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) { bad++; }
    }
    bad += diff3(arr[1], 40);
    bad += diff3(s, 10);
    bad += diff3(mk3(20), 20);
    bad += late3(1, 2, 3, 4, 5, 6, 7, s, 10);
    bad += late3(1, 2, 3, 4, 5, 6, 7, echo3(s), 10);
    if (bad) { printf("size 3: %d bad\n", bad); }
    return bad == 0;
}

S4 mk4(i64 seed) {
    S4 s;
    i64 i;
    for (i = 0; i < 4; i++) { s.b[i] = seed + i * 3; }
    return s;
}

/* 0 when `s` holds what Mk4(seed) made */
i64 diff4(S4 s, i64 seed) {
    i64 i, bad = 0;
    for (i = 0; i < 4; i++) { if (s.b[i] != (seed + i * 3) & 0xFF) { bad++; } }
    return bad;
}

/* The same, with the class past the argument registers */
i64 late4(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, S4 s, i64 seed) {
    if (a + b + c + d + e + f + g != 28) { return 100; }
    return diff4(s, seed);
}

/* Returns a parameter straight back */
S4 echo4(S4 s) { return s; }

bool check4() {
    S4 arr[3];
    S4 s = mk4(10);
    i64 i, bad = 0;
    mem.set(arr, 0xEE, sizeof(arr));
    arr[1] = echo4(mk4(40));
    for (i = 0; i < 4; i++) {
        if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) { bad++; }
    }
    bad += diff4(arr[1], 40);
    bad += diff4(s, 10);
    bad += diff4(mk4(20), 20);
    bad += late4(1, 2, 3, 4, 5, 6, 7, s, 10);
    bad += late4(1, 2, 3, 4, 5, 6, 7, echo4(s), 10);
    if (bad) { printf("size 4: %d bad\n", bad); }
    return bad == 0;
}

S5 mk5(i64 seed) {
    S5 s;
    i64 i;
    for (i = 0; i < 5; i++) { s.b[i] = seed + i * 3; }
    return s;
}

/* 0 when `s` holds what Mk5(seed) made */
i64 diff5(S5 s, i64 seed) {
    i64 i, bad = 0;
    for (i = 0; i < 5; i++) { if (s.b[i] != (seed + i * 3) & 0xFF) { bad++; } }
    return bad;
}

/* The same, with the class past the argument registers */
i64 late5(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, S5 s, i64 seed) {
    if (a + b + c + d + e + f + g != 28) { return 100; }
    return diff5(s, seed);
}

/* Returns a parameter straight back */
S5 echo5(S5 s) { return s; }

bool check5() {
    S5 arr[3];
    S5 s = mk5(10);
    i64 i, bad = 0;
    mem.set(arr, 0xEE, sizeof(arr));
    arr[1] = echo5(mk5(40));
    for (i = 0; i < 5; i++) {
        if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) { bad++; }
    }
    bad += diff5(arr[1], 40);
    bad += diff5(s, 10);
    bad += diff5(mk5(20), 20);
    bad += late5(1, 2, 3, 4, 5, 6, 7, s, 10);
    bad += late5(1, 2, 3, 4, 5, 6, 7, echo5(s), 10);
    if (bad) { printf("size 5: %d bad\n", bad); }
    return bad == 0;
}

S6 mk6(i64 seed) {
    S6 s;
    i64 i;
    for (i = 0; i < 6; i++) { s.b[i] = seed + i * 3; }
    return s;
}

/* 0 when `s` holds what Mk6(seed) made */
i64 diff6(S6 s, i64 seed) {
    i64 i, bad = 0;
    for (i = 0; i < 6; i++) { if (s.b[i] != (seed + i * 3) & 0xFF) { bad++; } }
    return bad;
}

/* The same, with the class past the argument registers */
i64 late6(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, S6 s, i64 seed) {
    if (a + b + c + d + e + f + g != 28) { return 100; }
    return diff6(s, seed);
}

/* Returns a parameter straight back */
S6 echo6(S6 s) { return s; }

bool check6() {
    S6 arr[3];
    S6 s = mk6(10);
    i64 i, bad = 0;
    mem.set(arr, 0xEE, sizeof(arr));
    arr[1] = echo6(mk6(40));
    for (i = 0; i < 6; i++) {
        if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) { bad++; }
    }
    bad += diff6(arr[1], 40);
    bad += diff6(s, 10);
    bad += diff6(mk6(20), 20);
    bad += late6(1, 2, 3, 4, 5, 6, 7, s, 10);
    bad += late6(1, 2, 3, 4, 5, 6, 7, echo6(s), 10);
    if (bad) { printf("size 6: %d bad\n", bad); }
    return bad == 0;
}

S7 mk7(i64 seed) {
    S7 s;
    i64 i;
    for (i = 0; i < 7; i++) { s.b[i] = seed + i * 3; }
    return s;
}

/* 0 when `s` holds what Mk7(seed) made */
i64 diff7(S7 s, i64 seed) {
    i64 i, bad = 0;
    for (i = 0; i < 7; i++) { if (s.b[i] != (seed + i * 3) & 0xFF) { bad++; } }
    return bad;
}

/* The same, with the class past the argument registers */
i64 late7(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, S7 s, i64 seed) {
    if (a + b + c + d + e + f + g != 28) { return 100; }
    return diff7(s, seed);
}

/* Returns a parameter straight back */
S7 echo7(S7 s) { return s; }

bool check7() {
    S7 arr[3];
    S7 s = mk7(10);
    i64 i, bad = 0;
    mem.set(arr, 0xEE, sizeof(arr));
    arr[1] = echo7(mk7(40));
    for (i = 0; i < 7; i++) {
        if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) { bad++; }
    }
    bad += diff7(arr[1], 40);
    bad += diff7(s, 10);
    bad += diff7(mk7(20), 20);
    bad += late7(1, 2, 3, 4, 5, 6, 7, s, 10);
    bad += late7(1, 2, 3, 4, 5, 6, 7, echo7(s), 10);
    if (bad) { printf("size 7: %d bad\n", bad); }
    return bad == 0;
}

S8 mk8(i64 seed) {
    S8 s;
    i64 i;
    for (i = 0; i < 8; i++) { s.b[i] = seed + i * 3; }
    return s;
}

/* 0 when `s` holds what Mk8(seed) made */
i64 diff8(S8 s, i64 seed) {
    i64 i, bad = 0;
    for (i = 0; i < 8; i++) { if (s.b[i] != (seed + i * 3) & 0xFF) { bad++; } }
    return bad;
}

/* The same, with the class past the argument registers */
i64 late8(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, S8 s, i64 seed) {
    if (a + b + c + d + e + f + g != 28) { return 100; }
    return diff8(s, seed);
}

/* Returns a parameter straight back */
S8 echo8(S8 s) { return s; }

bool check8() {
    S8 arr[3];
    S8 s = mk8(10);
    i64 i, bad = 0;
    mem.set(arr, 0xEE, sizeof(arr));
    arr[1] = echo8(mk8(40));
    for (i = 0; i < 8; i++) {
        if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) { bad++; }
    }
    bad += diff8(arr[1], 40);
    bad += diff8(s, 10);
    bad += diff8(mk8(20), 20);
    bad += late8(1, 2, 3, 4, 5, 6, 7, s, 10);
    bad += late8(1, 2, 3, 4, 5, 6, 7, echo8(s), 10);
    if (bad) { printf("size 8: %d bad\n", bad); }
    return bad == 0;
}

S9 mk9(i64 seed) {
    S9 s;
    i64 i;
    for (i = 0; i < 9; i++) { s.b[i] = seed + i * 3; }
    return s;
}

/* 0 when `s` holds what Mk9(seed) made */
i64 diff9(S9 s, i64 seed) {
    i64 i, bad = 0;
    for (i = 0; i < 9; i++) { if (s.b[i] != (seed + i * 3) & 0xFF) { bad++; } }
    return bad;
}

/* The same, with the class past the argument registers */
i64 late9(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, S9 s, i64 seed) {
    if (a + b + c + d + e + f + g != 28) { return 100; }
    return diff9(s, seed);
}

/* Returns a parameter straight back */
S9 echo9(S9 s) { return s; }

bool check9() {
    S9 arr[3];
    S9 s = mk9(10);
    i64 i, bad = 0;
    mem.set(arr, 0xEE, sizeof(arr));
    arr[1] = echo9(mk9(40));
    for (i = 0; i < 9; i++) {
        if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) { bad++; }
    }
    bad += diff9(arr[1], 40);
    bad += diff9(s, 10);
    bad += diff9(mk9(20), 20);
    bad += late9(1, 2, 3, 4, 5, 6, 7, s, 10);
    bad += late9(1, 2, 3, 4, 5, 6, 7, echo9(s), 10);
    if (bad) { printf("size 9: %d bad\n", bad); }
    return bad == 0;
}

S10 mk10(i64 seed) {
    S10 s;
    i64 i;
    for (i = 0; i < 10; i++) { s.b[i] = seed + i * 3; }
    return s;
}

/* 0 when `s` holds what Mk10(seed) made */
i64 diff10(S10 s, i64 seed) {
    i64 i, bad = 0;
    for (i = 0; i < 10; i++) { if (s.b[i] != (seed + i * 3) & 0xFF) { bad++; } }
    return bad;
}

/* The same, with the class past the argument registers */
i64 late10(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, S10 s, i64 seed) {
    if (a + b + c + d + e + f + g != 28) { return 100; }
    return diff10(s, seed);
}

/* Returns a parameter straight back */
S10 echo10(S10 s) { return s; }

bool check10() {
    S10 arr[3];
    S10 s = mk10(10);
    i64 i, bad = 0;
    mem.set(arr, 0xEE, sizeof(arr));
    arr[1] = echo10(mk10(40));
    for (i = 0; i < 10; i++) {
        if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) { bad++; }
    }
    bad += diff10(arr[1], 40);
    bad += diff10(s, 10);
    bad += diff10(mk10(20), 20);
    bad += late10(1, 2, 3, 4, 5, 6, 7, s, 10);
    bad += late10(1, 2, 3, 4, 5, 6, 7, echo10(s), 10);
    if (bad) { printf("size 10: %d bad\n", bad); }
    return bad == 0;
}

S11 mk11(i64 seed) {
    S11 s;
    i64 i;
    for (i = 0; i < 11; i++) { s.b[i] = seed + i * 3; }
    return s;
}

/* 0 when `s` holds what Mk11(seed) made */
i64 diff11(S11 s, i64 seed) {
    i64 i, bad = 0;
    for (i = 0; i < 11; i++) { if (s.b[i] != (seed + i * 3) & 0xFF) { bad++; } }
    return bad;
}

/* The same, with the class past the argument registers */
i64 late11(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, S11 s, i64 seed) {
    if (a + b + c + d + e + f + g != 28) { return 100; }
    return diff11(s, seed);
}

/* Returns a parameter straight back */
S11 echo11(S11 s) { return s; }

bool check11() {
    S11 arr[3];
    S11 s = mk11(10);
    i64 i, bad = 0;
    mem.set(arr, 0xEE, sizeof(arr));
    arr[1] = echo11(mk11(40));
    for (i = 0; i < 11; i++) {
        if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) { bad++; }
    }
    bad += diff11(arr[1], 40);
    bad += diff11(s, 10);
    bad += diff11(mk11(20), 20);
    bad += late11(1, 2, 3, 4, 5, 6, 7, s, 10);
    bad += late11(1, 2, 3, 4, 5, 6, 7, echo11(s), 10);
    if (bad) { printf("size 11: %d bad\n", bad); }
    return bad == 0;
}

S12 mk12(i64 seed) {
    S12 s;
    i64 i;
    for (i = 0; i < 12; i++) { s.b[i] = seed + i * 3; }
    return s;
}

/* 0 when `s` holds what Mk12(seed) made */
i64 diff12(S12 s, i64 seed) {
    i64 i, bad = 0;
    for (i = 0; i < 12; i++) { if (s.b[i] != (seed + i * 3) & 0xFF) { bad++; } }
    return bad;
}

/* The same, with the class past the argument registers */
i64 late12(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, S12 s, i64 seed) {
    if (a + b + c + d + e + f + g != 28) { return 100; }
    return diff12(s, seed);
}

/* Returns a parameter straight back */
S12 echo12(S12 s) { return s; }

bool check12() {
    S12 arr[3];
    S12 s = mk12(10);
    i64 i, bad = 0;
    mem.set(arr, 0xEE, sizeof(arr));
    arr[1] = echo12(mk12(40));
    for (i = 0; i < 12; i++) {
        if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) { bad++; }
    }
    bad += diff12(arr[1], 40);
    bad += diff12(s, 10);
    bad += diff12(mk12(20), 20);
    bad += late12(1, 2, 3, 4, 5, 6, 7, s, 10);
    bad += late12(1, 2, 3, 4, 5, 6, 7, echo12(s), 10);
    if (bad) { printf("size 12: %d bad\n", bad); }
    return bad == 0;
}

S13 mk13(i64 seed) {
    S13 s;
    i64 i;
    for (i = 0; i < 13; i++) { s.b[i] = seed + i * 3; }
    return s;
}

/* 0 when `s` holds what Mk13(seed) made */
i64 diff13(S13 s, i64 seed) {
    i64 i, bad = 0;
    for (i = 0; i < 13; i++) { if (s.b[i] != (seed + i * 3) & 0xFF) { bad++; } }
    return bad;
}

/* The same, with the class past the argument registers */
i64 late13(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, S13 s, i64 seed) {
    if (a + b + c + d + e + f + g != 28) { return 100; }
    return diff13(s, seed);
}

/* Returns a parameter straight back */
S13 echo13(S13 s) { return s; }

bool check13() {
    S13 arr[3];
    S13 s = mk13(10);
    i64 i, bad = 0;
    mem.set(arr, 0xEE, sizeof(arr));
    arr[1] = echo13(mk13(40));
    for (i = 0; i < 13; i++) {
        if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) { bad++; }
    }
    bad += diff13(arr[1], 40);
    bad += diff13(s, 10);
    bad += diff13(mk13(20), 20);
    bad += late13(1, 2, 3, 4, 5, 6, 7, s, 10);
    bad += late13(1, 2, 3, 4, 5, 6, 7, echo13(s), 10);
    if (bad) { printf("size 13: %d bad\n", bad); }
    return bad == 0;
}

S14 mk14(i64 seed) {
    S14 s;
    i64 i;
    for (i = 0; i < 14; i++) { s.b[i] = seed + i * 3; }
    return s;
}

/* 0 when `s` holds what Mk14(seed) made */
i64 diff14(S14 s, i64 seed) {
    i64 i, bad = 0;
    for (i = 0; i < 14; i++) { if (s.b[i] != (seed + i * 3) & 0xFF) { bad++; } }
    return bad;
}

/* The same, with the class past the argument registers */
i64 late14(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, S14 s, i64 seed) {
    if (a + b + c + d + e + f + g != 28) { return 100; }
    return diff14(s, seed);
}

/* Returns a parameter straight back */
S14 echo14(S14 s) { return s; }

bool check14() {
    S14 arr[3];
    S14 s = mk14(10);
    i64 i, bad = 0;
    mem.set(arr, 0xEE, sizeof(arr));
    arr[1] = echo14(mk14(40));
    for (i = 0; i < 14; i++) {
        if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) { bad++; }
    }
    bad += diff14(arr[1], 40);
    bad += diff14(s, 10);
    bad += diff14(mk14(20), 20);
    bad += late14(1, 2, 3, 4, 5, 6, 7, s, 10);
    bad += late14(1, 2, 3, 4, 5, 6, 7, echo14(s), 10);
    if (bad) { printf("size 14: %d bad\n", bad); }
    return bad == 0;
}

S15 mk15(i64 seed) {
    S15 s;
    i64 i;
    for (i = 0; i < 15; i++) { s.b[i] = seed + i * 3; }
    return s;
}

/* 0 when `s` holds what Mk15(seed) made */
i64 diff15(S15 s, i64 seed) {
    i64 i, bad = 0;
    for (i = 0; i < 15; i++) { if (s.b[i] != (seed + i * 3) & 0xFF) { bad++; } }
    return bad;
}

/* The same, with the class past the argument registers */
i64 late15(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, S15 s, i64 seed) {
    if (a + b + c + d + e + f + g != 28) { return 100; }
    return diff15(s, seed);
}

/* Returns a parameter straight back */
S15 echo15(S15 s) { return s; }

bool check15() {
    S15 arr[3];
    S15 s = mk15(10);
    i64 i, bad = 0;
    mem.set(arr, 0xEE, sizeof(arr));
    arr[1] = echo15(mk15(40));
    for (i = 0; i < 15; i++) {
        if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) { bad++; }
    }
    bad += diff15(arr[1], 40);
    bad += diff15(s, 10);
    bad += diff15(mk15(20), 20);
    bad += late15(1, 2, 3, 4, 5, 6, 7, s, 10);
    bad += late15(1, 2, 3, 4, 5, 6, 7, echo15(s), 10);
    if (bad) { printf("size 15: %d bad\n", bad); }
    return bad == 0;
}

S16 mk16(i64 seed) {
    S16 s;
    i64 i;
    for (i = 0; i < 16; i++) { s.b[i] = seed + i * 3; }
    return s;
}

/* 0 when `s` holds what Mk16(seed) made */
i64 diff16(S16 s, i64 seed) {
    i64 i, bad = 0;
    for (i = 0; i < 16; i++) { if (s.b[i] != (seed + i * 3) & 0xFF) { bad++; } }
    return bad;
}

/* The same, with the class past the argument registers */
i64 late16(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, S16 s, i64 seed) {
    if (a + b + c + d + e + f + g != 28) { return 100; }
    return diff16(s, seed);
}

/* Returns a parameter straight back */
S16 echo16(S16 s) { return s; }

bool check16() {
    S16 arr[3];
    S16 s = mk16(10);
    i64 i, bad = 0;
    mem.set(arr, 0xEE, sizeof(arr));
    arr[1] = echo16(mk16(40));
    for (i = 0; i < 16; i++) {
        if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) { bad++; }
    }
    bad += diff16(arr[1], 40);
    bad += diff16(s, 10);
    bad += diff16(mk16(20), 20);
    bad += late16(1, 2, 3, 4, 5, 6, 7, s, 10);
    bad += late16(1, 2, 3, 4, 5, 6, 7, echo16(s), 10);
    if (bad) { printf("size 16: %d bad\n", bad); }
    return bad == 0;
}

struct F3 { f32 x, y, z; };          /* HFA on arm64, two SSE eightbytes */
struct D3 { f64 d; u8 b[3]; };       /* SSE then a 3-byte INTEGER part */

F3 mkF3(i64 seed) {
    F3 s;
    s.x = seed; s.y = seed + 0.5; s.z = seed + 1.25;
    return s;
}

i64 diffF3(F3 s, i64 seed) {
    return (s.x != seed) + (s.y != seed + 0.5) + (s.z != seed + 1.25);
}

D3 mkD3(i64 seed) {
    D3 s;
    s.d = seed + 0.75;
    s.b[0] = seed; s.b[1] = seed + 1; s.b[2] = seed + 2;
    return s;
}

i64 diffD3(D3 s, i64 seed) {
    return (s.d != seed + 0.75) + (s.b[0] != seed) + (s.b[1] != seed + 1) +
                 (s.b[2] != seed + 2);
}

i64 lateD3(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, D3 s, i64 seed) {
    if (a + b + c + d + e + f != 21) { return 100; }
    return diffD3(s, seed);
}

D3 echoD3(D3 s) { return s; }

bool checkFloats() {
    D3 arr[3];
    D3 s = mkD3(10);
    F3 f = mkF3(7);
    i64 bad = 0;
    mem.set(arr, 0xEE, sizeof(arr));
    arr[1] = echoD3(mkD3(40));
    if (arr[0].b[2] != 0xEE || arr[2].b[0] != 0xEE) { bad++; }
    bad += diffD3(arr[1], 40);
    bad += diffD3(s, 10);
    bad += lateD3(1, 2, 3, 4, 5, 6, s, 10);
    bad += diffF3(f, 7);
    bad += diffF3(mkF3(9), 9);
    if (bad) { printf("floats: %d bad\n", bad); }
    return bad == 0;
}

i32 main() {
    printf("Test - by-value classes of 1 to 16 bytes: ");
    i64 tests = 0;
    i64 correct = 0;

    tests++;
    if (check1()) { correct++; }
    tests++;
    if (check2()) { correct++; }
    tests++;
    if (check3()) { correct++; }
    tests++;
    if (check4()) { correct++; }
    tests++;
    if (check5()) { correct++; }
    tests++;
    if (check6()) { correct++; }
    tests++;
    if (check7()) { correct++; }
    tests++;
    if (check8()) { correct++; }
    tests++;
    if (check9()) { correct++; }
    tests++;
    if (check10()) { correct++; }
    tests++;
    if (check11()) { correct++; }
    tests++;
    if (check12()) { correct++; }
    tests++;
    if (check13()) { correct++; }
    tests++;
    if (check14()) { correct++; }
    tests++;
    if (check15()) { correct++; }
    tests++;
    if (check16()) { correct++; }
    tests++;
    if (checkFloats()) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
