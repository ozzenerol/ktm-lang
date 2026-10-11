#include "testhelper.ktm"

/* File-scope variables with initialisers are run by a synthetic entry
 * point that must also call the user's Main. The fact that Main runs at
 * all (so this file produces output) is itself the core regression
 * guard - before the fix the program initialised the globals and exited
 * without ever entering Main. */

i64 gInt   = 42;
i64 gNeg   = -7;
i64 gExpr  = 3 + 4 * 2;     /* 11 */
f64 gF64   = 1.5;
f32 gF32   = 2.25;
u8  gChar  = 'A';
u8 *gStr   = "ktm";
auto gAutoI = 99;          /* inferred i64 */
auto gAutoF = 3.5;         /* inferred f64 */

bool globalScalars() {
    i64 tests = 7, correct = 0;
    if (gInt == 42) { correct++; }
    if (gNeg == -7) { correct++; }
    if (gExpr == 11) { correct++; }
    if (gChar == 'A') { correct++; }
    if (gStr[0] == 'k' && gStr[2] == 'm' && !gStr[3]) { correct++; }
    if (gAutoI == 99) { correct++; }
    if (gAutoF == 3.5) { correct++; }
    return correct == tests;
}

bool globalFloats() {
    i64 tests = 3, correct = 0;
    if (gF64 == 1.5) { correct++; }
    if (gF32 == 2.25) { correct++; }
    if (gF64 + gF32 == 3.75) { correct++; }
    return correct == tests;
}

bool globalMutation() {
    i64 tests = 2, correct = 0;
    /* Globals are writable after their initialiser runs. */
    gInt = gInt + 8;
    if (gInt == 50) { correct++; }
    gF32 = gF32 * 2.0;
    if (gF32 == 4.5) { correct++; }
    return correct == tests;
}

i32 main() {
    printf("Test - Global initialisers:\n");
    i64 tests = 3, correct = 0;
    if (globalScalars()) { correct++; }
    if (globalFloats()) { correct++; }
    if (globalMutation()) { correct++; }
    PrintResult(correct,tests);
    printf("====\n");
    return 0;
}
