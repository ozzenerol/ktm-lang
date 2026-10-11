import test;

import mem;

struct Example {
    i64 i1;
    i64 i2;
    i64 i3;
    i64 x;
    i64 i4;
};

void assign(i64 *i) {
    *i = 9;
}

i32 main() {
    printf("Test Assigning pointer to a pointer from a class, no brackets: ");
    Example *e = mem.alloc(sizeof(Example));
    i64 *p = &e.x;
    assign(p);
    test.printResult(*p == 9, 1);
    mem.free(e);
    printf("====\n");
    return 0;
}
