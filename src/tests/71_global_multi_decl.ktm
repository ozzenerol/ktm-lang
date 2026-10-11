#include "testhelper.ktm"

/* Several declarators in one global declaration - `i64 a = 1, b = 2;`
 * at file scope - failed with "unexpected character `,`" and left the
 * later names undefined. Inside a function it always worked. */

struct Point {
    i64 x, y;
};

i64 gA = 1, gB = 2;
i64 gC, gD = 5, gE;
u8 *gS = "x", *gT, gU = 'A';      /* `*` binds per declarator */
i64 gArr[3] = {1,2,3}, gN = 3;
Point gP, gQ;
Point gR = {7,8}, *gPp;
static i64 gSt1 = 10, gSt2 = 20;
pub i64 gPb1 = 30, *gPb2, gPb3[2];
f64 gF1 = 1.5, gF2 = 2.25, gF3;
i64 gI1 = 4, gI2;       /* uses an earlier declarator */
u8 gBuf[8] = "hi", *gBp;
auto gAu1 = 6, gAu2 = 7;

/* Top-level statements run before Main and see every declarator. */
i64 gTop;

i32 main() {
    gI2 = gI1 * 3;
    gTop = gA + gB + gD + gArr[2] + gSt2;
    gT = gS;
    gPp = &gR;
    gPb2 = &gPb1;
    gPb3[1] = 99;
    gC = gA + 100;
    gP.x = 11;
    gQ.y = 22;
    gF3 = gF1 + gF2;
    i64 correct = 0, tests = 13;

    printf("Test - several declarations in one global statement: ");

    if (gA + gB == 3) { correct++; }
    if (gC == 101 && gD == 5 && gE == 0) { correct++; }
    if (gS[0] == 'x' && gT == gS && gU == 'A' && sizeof(gU) == 1) { correct++; }
    if (gArr[0] == 1 && gArr[1] == 2 && gArr[2] == 3 && gN == 3) { correct++; }
    if (gP.x == 11 && gQ.y == 22 && sizeof(gQ) == 16) { correct++; }
    if (gR.x == 7 && gPp.y == 8) { correct++; }
    if (gSt1 == 10 && gSt2 == 20) { correct++; }
    if (gPb1 == 30 && *gPb2 == 30 && gPb3[1] == 99 && sizeof(gPb3) == 16) { correct++; }
    if (gF1 == 1.5 && gF2 == 2.25 && gF3 == 3.75) { correct++; }
    if (gI1 == 4 && gI2 == 12) { correct++; }
    if (gBuf[0] == 'h' && sizeof(gBuf) == 8 && gBp == null) { correct++; }
    if (gAu1 + gAu2 == 13) { correct++; }
    if (gTop == 31) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
