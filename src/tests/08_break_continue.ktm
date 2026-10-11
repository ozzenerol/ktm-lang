#include "testhelper.ktm"

i64 testWhileBreak() {
    i64 i = 0;
    while (i != 5) {
        i++;
        if (i == 3) {
            break;
        }
    }
    return i == 3;
}

i64 testWhileContinue()
{ // j should never get incremented
    i64 i = 0, j = 0;
    while (i != 5) {
        if (i != 5) {
            i++;
            continue;
        }
        j++;
    }
    return j == 0;
}

i64 testForBreak() {
    i64 j = 0;
    for (i64 i = 0; i < 5; ++i) {
        if (j == 3) {
            break;
        }
        j++;
    }
    return j == 3;
}

i64 testForContinue()
{ // j should get incremented once
    i64 j = 0;
    for (i64 i = 0; i < 5; i++) {
        if (i < 4) {
            continue;
        }
        j++;
    }
    return j == 1;
}

i32 main() {
    printf("Test - 'break' and 'continue' inside while and for loops: ");
    i64 correct = 0, tests = 4;
    correct += testWhileBreak();
    correct += testWhileContinue();
    correct += testForBreak();
    correct += testForContinue();
    PrintResult(correct,tests);
    printf("====\n");
    return 0;
}
