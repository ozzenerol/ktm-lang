import test;

import mem;

void assignI64(i64 *ptr) {
    *ptr = 360;
}

bool arrayJuggling() {
    i64 *ptr = 0;
    i64 arr[5][5];
    arr[0][0] = 0;
    arr[0][0] += 1; // arr[0][0] = 1
    arr[0][0]++;    // arr[0][0] = 2
    ++(arr[0][0]);  // arr[0][0] = 3
    ptr = &arr[0][0];
    *++ptr;        // ptr is now pointing at arr[0][1]
    *ptr = 69;     // arr[0][1] = 69
    return arr[0][1] == 69 && arr[0][0] == 3;
}

i32 main() {
    printf("Test - Juggling an array MAlloc(i64 * 10): ");
    i64 correct = 0, tests = 4;
    i64 *arr = mem.alloc(sizeof(i64) * 10);
    i64 *ptr = &arr[2];
    assignI64(ptr);
    if (*ptr == 360) {
        correct++;
    }

    arr[2] = 420;
    if (arr[2] == 420 && *ptr == 420) {
        correct++;
    }

    *ptr = 10;
    if (arr[2] == 10 && *ptr == 10) {
        correct++;
    }

    if (arrayJuggling()) { correct++; }

    test.printResult(correct,tests);
    printf("====\n");
    return 0;
}
