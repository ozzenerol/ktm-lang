import test;

void testIfElse() {
    printf("Test - if, else if, else: ");
    i64 testCases = 4, passed = 0;
    i64 i = 0, j = 0;
    if (i == 0) {
        passed++;
    }

    i = 9;
    if (i == 0) {
        test.printResult(passed,testCases);
        return;
    } else if (i == 9) {
        passed++;
    } else {
        test.printResult(passed,testCases);
        return;
    }

    i = 10;
    j = 420;
    if (i == 0 && j == 69) {
        test.printResult(passed,testCases);
        return;
    } else if (i == 10 && j == 439) {
        test.printResult(passed,testCases);
        return;
    } else {
        passed++;
    }

    i = 10;
    j = 7;
    if (i == 7 || j == 7) {
        passed++;
    }
    test.printResult(passed,testCases);
}

i32 main() {
    testIfElse();
    printf("====\n");
    return 0;
}
