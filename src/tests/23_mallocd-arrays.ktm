import mem, test;

struct Point {
    i64 x;
    i64 y;
};

void intArray() {
    i64 *arr = mem.alloc(sizeof(i64) * 10);
    i64 j = 1, correct = 0, tests = 10;
    arr[0] = 1;
    arr[1] = 2;
    arr[2] = 3;
    arr[3] = 4;
    arr[4] = 5;
    arr[5] = 6;
    arr[6] = 7;
    arr[7] = 8;
    arr[8] = 9;
    arr[9] = 10;

    if (arr[0] == 1)  { correct++; }
    if (arr[1] == 2)  { correct++; }
    if (arr[2] == 3)  { correct++; }
    if (arr[3] == 4)  { correct++; }
    if (arr[4] == 5)  { correct++; }
    if (arr[5] == 6)  { correct++; }
    if (arr[6] == 7)  { correct++; }
    if (arr[7] == 8)  { correct++; }
    if (arr[8] == 9)  { correct++; }
    if (arr[9] == 10) { correct++; }

    mem.free(arr);
    test.printResult(correct,tests);
}

void pointerArray() {
    i64 j = 1, correct = 0, tests = 2;
    Point *arr = mem.alloc(sizeof(Point *) * 10);

    arr[8].y = 420;
    arr[0].x = 10;

    if (arr[0].x == 10) { correct++; }
    if (arr[8].y == 420) { correct++; }

    mem.free(arr);
    test.printResult(correct,tests);
}

i32 main() {
    printf("Malloced Arrays:\n");
    intArray();
    pointerArray();
    printf("=====\n");
    return 0;
}
