#include "testhelper.ktm"

/* Relational comparisons (`<`, `<=`, `>`, `>=`) between two pointers
 * of the same type. Used to be rejected even though `astIsValidPointerOp`
 * already allowed them; pointer arithmetic loops (e.g. `while (p<end)`)
 * depended on this. */

bool u8PtrCompare() {
    u8 buf[16];
    u8 *a = buf;
    u8 *b = buf + 7;
    return a < b &&
                 b > a &&
                 a <= a &&
                 b >= b &&
                 !(a > b) &&
                 !(b < a);
}

bool i64PtrCompare() {
    i64 arr[4];
    i64 *p = arr;
    i64 *q = arr + 3;
    return p < q && q > p && !(p > q);
}

bool whileLoopOverBuffer() {
    u8 buf[5] = {1, 2, 3, 4, 5};
    u8 *p = buf;
    u8 *end = buf + 5;
    i64 sum = 0;
    while (p < end) {
        sum += *p;
        p++;
    }
    return sum == 15;
}

i32 main() {
    printf("Test - Pointer relational comparisons: ");
    i64 correct = 0, tests = 3;
    if (u8PtrCompare()) { correct++; }
    if (i64PtrCompare()) { correct++; }
    if (whileLoopOverBuffer()) { correct++; }
    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
