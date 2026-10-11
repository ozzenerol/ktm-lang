import test;

import mem;

struct Point {
    i64 x;
    i64 y;
};

i32 main() {
    printf("Test - Pointer to a pointer from a class: ");
    Point *p = mem.alloc(sizeof(Point *));
    p.y = 420; // assign 420
    Point **ptr = &p; // Take a pointer to pointer p
    Point *x = *ptr;  // Dereference to get the pointer to p
    test.printResult(x.y == 420, 1); // x should be the original value we set
    printf("=====\n");
    return 0;
}
