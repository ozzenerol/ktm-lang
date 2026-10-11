#include "testhelper.ktm"

import os;

/* aarch64 add/sub immediates encode 12 bits (optionally lsl #12);
 * frames past 4095 bytes once emitted unencodable prologue/epilogue
 * adjusts, `add x0, x29, #-big` local addresses, and out-of-range
 * ldr/str displacements. Every function here carries an 8KB pad so
 * all of those paths run in the >4095 regime. */

struct BigPair {
    i64 a;
    i64 b;
};

struct BigHfa {
    f64 x;
    f64 y;
};

struct BigFive {
    i64 vals[5];
};

i64 correct = 0;
i64 tests = 10;

i64 sumByValue(BigFive f)
{ /* 40-byte by-value param copied into a big frame */
    u8 pad[8192];
    pad[0] = 7;
    pad[8191] = 9;
    i64 s = 0;
    for (i64 i = 0; i < 5; i++) { s = s + f.vals[i]; }
    return s + pad[0] + pad[8191];
}

BigPair makePair(i64 base)
{ /* <=16-byte INTEGER-class return out of a big frame */
    u8 pad[8192];
    pad[100] = 1;
    BigPair p;
    p.a = base + pad[100];
    p.b = base * 2;
    return p;
}

BigHfa makeHfa()
{ /* HFA return out of a big frame */
    u8 pad[8192];
    pad[50] = 2;
    i64 t = pad[50];
    BigHfa h;
    h.x = 1.5 + t;
    h.y = 2.5;
    return h;
}

void runMain() {
    u8 buf[8192];      /* prologue/epilogue immediate split */
    i64 sentinel = 42; /* scalar slot at a deep offset */
    buf[0] = 11;
    buf[8191] = 22;    /* constant-index store, disp > 4095 */
    u8 *p = &buf[8191]; /* address-of at a deep offset */
    i64 mid = 4000;
    buf[mid] = 33;      /* register-indexed store */

    BigFive five;
    for (i64 i = 0; i < 5; i++) { five.vals[i] = i + 1; }

    if (buf[0] == 11) { correct++; }
    if (buf[8191] == 22) { correct++; }
    if (*p == 22) { correct++; }
    if (buf[mid] == 33) { correct++; }
    if (sentinel == 42) { correct++; }
    if (sumByValue(five) == 31) { correct++; } /* 15 + 7 + 9 */

    BigPair pr = makePair(10);
    if (pr.a == 11) { correct++; }
    if (pr.b == 20) { correct++; }

    BigHfa h = makeHfa();
    if (h.x == 3.5) { correct++; }
    if (h.y == 2.5) { correct++; }
}


void main() {
    printf("Test - big stack frames: ");
    runMain();
    PrintResult(correct, tests);
    printf("===\n");
    os.exit(0);
}
