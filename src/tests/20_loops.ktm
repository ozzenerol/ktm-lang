#include "testhelper.ktm"

void testForLoop() {
    printf("Testing For Loop: ");
    i64 testCases = 2, passed = 0;

    i64 x = 0, i = 0;
    for (i64 i = 0; i < 10; ++i) {
        x = i;
    }

    if (x == 9) {
        passed++;
    }

    for (i = x; i >= 0; --i) {
        x = i;
    }

    if (x == 0) {
        passed++;
    }

    PrintResult(passed,testCases);
}

void testWhileLoop() {
    printf("Testing while loop: ");
    i64 i = 0;
    i64 passed = 0, testCases = 1;
    while (i < 9) {
        i++;
    }
    if (i == 9) {
        passed++;
    }
    PrintResult(passed,testCases);
}

void testDoWhileLoop() {
    printf("Testing do-while loop: ");
    i64 i = 10;
    do {
        i = i + 1;

    } while (i < 10);
    PrintResult(i == 11, 1);
}

i32 main() {
    printf("Loop test suite\n");
    testForLoop();
    testWhileLoop();  
    testDoWhileLoop();
    printf("=====\n");
    return 0;
}
