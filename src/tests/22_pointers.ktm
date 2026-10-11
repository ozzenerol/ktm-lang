#include "testhelper.ktm"

import mem;

struct Point {
    i64 x;
    i64 y;
};

void pointerAddressing() {
    i64 *j = 0, correct = 0, tests = 2;
    Point *ptr = mem.alloc(sizeof(Point *));

    ptr.x = 10;
    if (ptr.x == 10) {
        correct++;
    }

    j = &(ptr.x);
    if (*j == 10) {
        correct++;
    }
    mem.free(ptr);
    PrintResult(correct,tests);
}

i32 main() {
    printf("Addressing: ");
    pointerAddressing();
    printf("====\n");
    return 0;
}
