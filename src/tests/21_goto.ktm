#include "testhelper.ktm"

bool testGoto() {
    for (i64 i = 0; i < 9; ++i) {
        if (i == 4) {
            goto passed;
        }
    } 
    return false;

passed:
    return true;
}

/* test that we can use the same label between functions */
bool testGoto2() {
    for (i64 i = 0; i < 9; ++i) {
        if (i == 4) {
            goto passed;
        }
    } 
    return false;

passed:
    return true;
}

i32 main() {
    i64 correct = 0, testCases = 2;
    printf("Testing GOTO: ");
    if (testGoto()) { ++correct; }
    if (testGoto2()) { ++correct; }
    PrintResult(correct,testCases);
    printf("=====\n");
    return 0;
}
