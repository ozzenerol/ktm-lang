#include "testhelper.ktm"

i64 add(i64 a, i64 b=76) {
    return a + b;
}

i64 add2(i64 a=10, i64 b=43) {
    return a + b;
}

i32 main() {
    printf("Test - Default function parameters: ");
    i64 i = add(20);
    PrintResult(i == 96 && add2(,43) == 53, 1);
    printf("====\n");
    return 0;
}
