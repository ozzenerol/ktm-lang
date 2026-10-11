#include "testhelper.ktm"

/* A brace initialiser with fewer items than the object has elements or
 * fields must zero the rest, as in C. A global `P gp = {1};` only
 * emitted the first item, so the next global's bytes showed up in
 * gp.b, and a local `P lp = {9};` left the other fields as stack
 * garbage. Nested lists `{{1},{2}}` also packed their items together
 * instead of starting each at its own element. */

struct P133 {
    i64 a, b, c;
};

struct Arr133 {
    i64 v[4];
    i64 w;
};

struct Nest133 {
    i64 t;
    P133 p;
    i64 u;
};

P133    gP = {1};
i64     gNext = -1;
i64     gArr[4] = {1, 2};
i64     gNext2 = -1;
i64     gM[3][3] = {{1}, {2, 3}};
P133    gPs[3] = {{1}, {2, 3}};
Arr133  gA = {{1, 2}, 9};
Nest133 gN = {1, {2}};
f64     gF[3] = {1.5};
i32     gI[5] = {7};
u8      gB[6] = {1, 2};
i64     gFull[3] = {4, 5, 6};
i64     gLast = -1;

bool pIs(P133 *p, i64 a, i64 b, i64 c) {
    return p.a == a && p.b == b && p.c == c;
}

i64 dirty() {
    i64 junk[512], i, sum = 0;
    for (i = 0; i < 512; i++) { junk[i] = -1 - i; }
    for (i = 0; i < 512; i++) { sum += junk[i]; }
    return sum;
}

i64 locals() {
    i64 correct = 0, i;

    P133 p = {9};
    if (pIs(&p, 9, 0, 0)) { correct++; }

    i64 arr[4] = {1, 2};
    if (arr[0] == 1 && arr[1] == 2 && arr[2] == 0 && arr[3] == 0) { correct++; }

    i64 m[3][3] = {{1}, {2, 3}};
    if (m[0][0] == 1 && m[0][1] == 0 && m[0][2] == 0 && m[1][0] == 2 &&
            m[1][1] == 3 && m[1][2] == 0 && m[2][0] == 0 && m[2][2] == 0) {
        correct++;
            }

    P133 ps[3] = {{1}, {2, 3}};
    if (pIs(&ps[0], 1, 0, 0) && pIs(&ps[1], 2, 3, 0) && pIs(&ps[2], 0, 0, 0)) {
        correct++;
    }

    Arr133 a = {{1, 2}, 9};
    if (a.v[0] == 1 && a.v[1] == 2 && a.v[2] == 0 && a.v[3] == 0 && a.w == 9) {
        correct++;
    }

    Nest133 n = {1, {2}};
    if (n.t == 1 && pIs(&n.p, 2, 0, 0) && n.u == 0) { correct++; }

    f64 f[3] = {1.5};
    i32 vI32[5] = {7};
    u8 b[6] = {1, 2};
    if (f[0] == 1.5 && f[1] == 0.0 && f[2] == 0.0) { correct++; }
    if (vI32[0] == 7 && vI32[1] == 0 && vI32[4] == 0) { correct++; }
    if (b[0] == 1 && b[1] == 2 && b[2] == 0 && b[5] == 0) { correct++; }

    /* Bigger than the inline-store limit: cleared with memset. */
    i64 big[40] = {1, 2};
    bool bigOk = big[0] == 1 && big[1] == 2;
    for (i = 2; i < 40; i++) {
        if (big[i]) { bigOk = false; }
    }
    if (bigOk) { correct++; }

    /* Fully initialised objects are unaffected. */
    i64 full[3] = {4, 5, 6};
    P133 pf = {7, 8, 9};
    if (full[0] == 4 && full[2] == 6 && pIs(&pf, 7, 8, 9)) { correct++; }

    /* Re-initialised on every pass of a loop. */
    bool loopOk = true;
    for (i = 0; i < 3; i++) {
        P133 lp = {i};
        if (!pIs(&lp, i, 0, 0)) { loopOk = false; }
        lp.b = 99;
        lp.c = 99;
    }
    if (loopOk) { correct++; }
    return correct;
}

i32 main() {
    printf("Test - initialiser zero fill: ");
    i64 correct = 0, tests = 24;

    if (pIs(&gP, 1, 0, 0)) { correct++; }
    if (gNext == -1 && gNext2 == -1 && gLast == -1) { correct++; }
    if (gArr[0] == 1 && gArr[1] == 2 && gArr[2] == 0 && gArr[3] == 0) {
        correct++;
    }
    if (gM[0][0] == 1 && gM[0][1] == 0 && gM[0][2] == 0 &&
            gM[1][0] == 2 && gM[1][1] == 3 && gM[1][2] == 0 &&
            gM[2][0] == 0 && gM[2][2] == 0) {
        correct++;
            }
    if (pIs(&gPs[0], 1, 0, 0) && pIs(&gPs[1], 2, 3, 0) &&
            pIs(&gPs[2], 0, 0, 0)) {
        correct++;
            }
    if (gA.v[0] == 1 && gA.v[1] == 2 && gA.v[2] == 0 && gA.v[3] == 0 &&
            gA.w == 9) {
        correct++;
            }
    if (gN.t == 1 && pIs(&gN.p, 2, 0, 0) && gN.u == 0) { correct++; }
    if (gF[0] == 1.5 && gF[1] == 0.0 && gF[2] == 0.0) { correct++; }
    if (gI[0] == 7 && gI[1] == 0 && gI[4] == 0) { correct++; }
    if (gB[0] == 1 && gB[1] == 2 && gB[2] == 0 && gB[5] == 0) { correct++; }
    if (gFull[0] == 4 && gFull[1] == 5 && gFull[2] == 6) { correct++; }

    /* Leave non-zero values where Locals' frame will be. */
    if (dirty() != 0) { correct++; }
    correct += locals();

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
