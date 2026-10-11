#include "testhelper.ktm"

void testIfElse() {
    printf("Testing if else\n");
    i64 testCases = 4, passed = 0;
    i64 i = 0, j = 0;
    if (i == 0) {
        passed++;
    }

    i = 9;
    if (i == 0) {
        PrintResult(passed,testCases);
        return;
    } else if (i == 9) {
        passed++;
    } else {
        PrintResult(passed,testCases);
        return;
    }

    i = 10;
    j = 420;
    if (i == 0 && j == 69) {
        PrintResult(passed,testCases);
        return;
    } else if (i == 10 && j == 439) {
        PrintResult(passed,testCases);
        return;
    } else {
        passed++;
    }

    i = 10;
    j = 7;
    if (i == 7 || j == 7) {
        passed++;
    }
    PrintResult(passed,testCases);
}

i32 main() {
    testIfElse();
    printf("=====\n");
    return 0;
}
