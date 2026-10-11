#include "testhelper.ktm"

import mem;

/* A function's frame size was kept in 16 bits (IrFunction.stack_space and
 * the prologues' total_stack were u16), so a function with more than about
 * 64 KiB of locals reserved only the size mod 65536: its locals lay below
 * the stack pointer and the next call wrote over them (65480 bytes of
 * array worked, 65481 didn't). Each level here fills its own array, lets a
 * stack-hungry helper run below it, recurses, and checks every byte after
 * the calls return. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 deep(i64 n)
{// ~100 KiB of stack below the caller's frame at n = 200
    i64 pad[64];
    mem.set(pad, n & 0xFF, sizeof(pad));
    if (n == 0) { return 0; }
    return deep(n - 1) + pad[n % 64];
}

/* Bytes of buf that aren't `v` */
i64 Bad(u8 *buf, i64 len, u8 v) {
    i64 i, bad = 0;
    for (i = 0; i < len; i++) { bad += buf[i] != v; }
    return bad;
}

/* Fill the frame's array, let Deep run below it, check it */
i64 fill(u8 *buf, i64 size, i64 depth) {
    mem.set(buf, 0x40 + depth, size);
    deep(200);
    return Bad(buf, size, 0x40 + depth);
}

i64 below(i64 depth) {
    u8 buf[65480];
    i64 bad = fill(buf, 65480, depth);
    if (depth > 0) { bad += below(depth - 1); }
    deep(200);
    return bad + Bad(buf, 65480, 0x40 + depth);
}

i64 above(i64 depth) {
    u8 buf[65481];
    i64 bad = fill(buf, 65481, depth);
    if (depth > 0) { bad += above(depth - 1); }
    deep(200);
    return bad + Bad(buf, 65481, 0x40 + depth);
}

i64 frame64K(i64 depth) {
    u8 buf[65536];
    i64 bad = fill(buf, 65536, depth);
    if (depth > 0) { bad += frame64K(depth - 1); }
    deep(200);
    return bad + Bad(buf, 65536, 0x40 + depth);
}

i64 frame65K(i64 depth) {
    u8 buf[66560];
    i64 bad = fill(buf, 66560, depth);
    if (depth > 0) { bad += frame65K(depth - 1); }
    deep(200);
    return bad + Bad(buf, 66560, 0x40 + depth);
}

i64 frame1M(i64 depth) {
    u8 buf[1048576];
    i64 bad = fill(buf, 1048576, depth);
    if (depth > 0) { bad += frame1M(depth - 1); }
    deep(200);
    return bad + Bad(buf, 1048576, 0x40 + depth);
}

i64 frame4M(i64 depth) {
    u8 buf[4194304];
    i64 bad = fill(buf, 4194304, depth);
    if (depth > 0) { bad += frame4M(depth - 1); }
    deep(200);
    return bad + Bad(buf, 4194304, 0x40 + depth);
}

/* Several big locals plus scalars around them in one frame */
i64 mixed() {
    i64 before = 0x1111;
    u8 a[40000];
    f64 f = 2.5;
    u8 b[40000];
    i64 after = 0x2222;
    mem.set(a, 'a', sizeof(a));
    mem.set(b, 'b', sizeof(b));
    deep(200);
    return Bad(a, sizeof(a), 'a') + Bad(b, sizeof(b), 'b') +
                 (before != 0x1111) + (after != 0x2222) + (f != 2.5);
}

i32 main() {
    printf("Test - stack frames over 64 KiB: ");
    check(below(0) == 0, "65480-byte frame");
    check(above(0) == 0, "65481-byte frame");
    check(frame64K(9) == 0, "64 KiB frames, 10 deep");
    check(frame65K(9) == 0, "65 KiB frames, 10 deep");
    check(frame1M(2) == 0, "1 MiB frames, 3 deep");
    check(frame4M(0) == 0, "4 MiB frame");
    check(mixed() == 0, "two 40000-byte arrays and scalars in one frame");
    PrintResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
