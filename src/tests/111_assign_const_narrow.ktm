#include "testhelper.ktm"

/* Assigning an integer constant to a narrower variable stored the
 * truncated value, but the assignment expression itself evaluated to the
 * untruncated constant: `i64 v = (u8 = 300);` gave v == 300, not 44. */

struct Narrow {
    u8 b;
    i16 h;
    u32 w;
};

u8 gU8;
i32 gI32;

i64 Id(i64 x) { return x; }

i32 main() {
    i64 correct = 0, tests = 24;

    printf("Test - assigning a constant to a narrow integer yields the truncated value: ");

    u8 u;
    i64 v = (u = 300);
    if (v == 44 && u == 44) { correct++; }

    /* Non-constant right side (already worked). */
    i64 w = 300;
    u8 u2;
    i64 v2 = (u2 = w);
    if (v2 == 44 && u2 == 44) { correct++; }

    /* Every narrow width, signed and unsigned, including negatives. */
    i8 c;
    if ((c = 200) == -56 && c == -56) { correct++; }
    u32 x;
    if ((x = 0x1FFFFFFFF) == 4294967295 && x == 4294967295) { correct++; }
    i16 s;
    if ((s = 40000) == -25536 && s == -25536) { correct++; }
    u16 us;
    if ((us = -1) == 65535 && us == 65535) { correct++; }
    i32 i3;
    if ((i3 = 0x180000000) == -2147483648 && i3 == -2147483648) { correct++; }
    if ((i3 = 0x7FFFFFFF) == 0x7FFFFFFF) { correct++; }
    u8 u3;
    if ((u3 = -1) == 255 && (u3 = 255) == 255) { correct++; }
    bool b;
    /* bool is 0 or 1 (value != 0), not the low byte (2) */
    if ((b = 258) == 1 && b == 1) { correct++; }

    /* Chained assignment through mixed widths. */
    u8 a;
    i16 bb;
    i64 r = (a = bb = 300);
    if (r == 44 && a == 44 && bb == 300) { correct++; }
    r = (bb = a = 300);
    if (r == 44 && a == 44 && bb == 44) { correct++; }

    /* As a condition: 256 truncates to 0. */
    i64 taken = 0;
    if ((u = 256)) { taken = 1; }
    if (!taken && u == 0) { correct++; }
    taken = 0;
    while ((u = 512)) { taken = 1; break; }
    if (!taken) { correct++; }

    /* Compound assignment. */
    u = 10;
    if ((u += 300) == 54 && u == 54) { correct++; }

    /* As a function argument and through memory targets. */
    if (Id((u = 300)) == 44) { correct++; }
    Narrow n;
    Narrow *np = &n;
    if ((n.b = 300) == 44 && n.b == 44) { correct++; }
    if ((np.h = 40000) == -25536 && n.h == -25536) { correct++; }
    if ((np.w = 0x3FFFFFFFF) == 4294967295 && n.w == 4294967295) { correct++; }
    u8 arr[2];
    if ((arr[1] = 257) == 1 && arr[1] == 1) { correct++; }
    if ((gU8 = 300) == 44 && gU8 == 44) { correct++; }
    if ((gI32 = 0x1FFFFFFFF) == -1 && gI32 == -1) { correct++; }

    /* 64-bit and float targets unchanged. */
    i64 big;
    if ((big = 0x123456789) == 0x123456789) { correct++; }
    f64 f;
    if ((f = 3) == 3.0) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
