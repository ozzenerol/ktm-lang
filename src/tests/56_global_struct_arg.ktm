#include "testhelper.ktm"

/* Passing a GLOBAL struct by value as a call argument. Regression for a bus
 * error: irLowerGlobal returned a scalar load of a class global's first 8
 * bytes instead of its address, so the by-value argument lowering treated
 * those bytes as the struct's address and dereferenced garbage. A global
 * aggregate must decay to its address (like an array / like a local struct).
 * Covers all three AAPCS/SysV classes: small integer (in regs), large
 * (passed indirectly), and an HFA (in vector regs). */

struct Small { i32 r, g, b, a; };   /* 16B integer aggregate */
struct Big   { i64 a, b, c; };      /* 24B -> indirect / MEMORY */
struct Hfa   { f32 x, y, z; };      /* homogeneous float aggregate */

Small gSmall = {1, 2, 3, 4};
Big   gBig   = {10, 20, 30};
Hfa   gHfa   = {1.5, 2.5, 3.5};

i64 sumSmall(Small s) { return s.r + s.g + s.b + s.a; }
i64 sumBig(Big b)     { return b.a + b.b + b.c; }
f64 sumHfa(Hfa h)     { return h.x + h.y + h.z; }

bool globalSmallArg() { return sumSmall(gSmall) == 10; }
bool globalBigArg()   { return sumBig(gBig) == 60; }
bool globalHfaArg()   { return sumHfa(gHfa) == 7.5; }

i32 main() {
    printf("Test - passing a global struct by value as an argument:\n");
    i64 tests = 3, correct = 0;
    if (globalSmallArg()) { correct++; }
    if (globalBigArg())   { correct++; }
    if (globalHfaArg())   { correct++; }
    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
