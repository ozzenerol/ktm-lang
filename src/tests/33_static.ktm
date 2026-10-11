#include "testhelper.ktm"

import libc, str;

i64 globalInt;

u8 *testStaticSetup(u8 *str) {
    static u8 buffer[128];
    libc.snprintf(buffer,sizeof(buffer),str);
    return buffer;
}

bool testStatic() {
    u8 *str = testStaticSetup("hello");
    if (!str.cmpLen(str,"hello",5)) {
        return true;
    }
    return false;
}

i64 testStaticIntSetup(i64 value) {
    static i64 staticInt = 32;
    if (value > 42) {
        staticInt = value;
    }
    return staticInt;
}

bool testStaticInt() {
    i64 tests = 3, correct = 0;
    i64 value = testStaticIntSetup(16);
    if (value == 32) {
        correct++;
    }

    value = testStaticIntSetup(420);
    if (value == 420) {
        correct++;
    }

    value = testStaticIntSetup(32);
    if (value == 420) {
        correct++;
    }

    return correct == tests;
}

void testGlobalSetup(i64 value) {
    globalInt = value;
}

bool testGlobal() {
    testGlobalSetup(69);
    if (globalInt == 69) {
        return true;
    }
    return false;
}

i32 main() {
    printf("Test - local static variables and global variables: \n");
    i64 tests = 3, correct = 0;
    if (testStatic())    { correct++; }
    if (testGlobal())    { correct++; }
    if (testStaticInt()) { correct++; }
    PrintResult(correct,tests);
    printf("====\n");
    return 0;
}
