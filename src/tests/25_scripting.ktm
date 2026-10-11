import test;

import libc, os;

/* While this is possible, it is quite difficult to read */

extern "c" void *malloc(u64 size);
extern "c" void free(void *ptr);
extern "c" i64 strncmp(u8 *s1, u8 *s2, i64 n);

i64 correct = 0;
i64 tests = 4;

struct MyClass {
    i64 i;
};

i64 x = 0;

MyClass *c;


i64 func(i64 y = 10) {
    return y;
}

void assign() {
    c.i = 89;
}



i32 runMain() {
    if (x == 45) { correct++; }
    if (c.i == 89) { correct++; }
    return 0;
}

void main() {
    printf("Test Scripting: ");
    c = libc.malloc(sizeof(MyClass));
    c.i = 0;
    while (c.i < 10) {
        ++c.i;
    }
    for (i64 i = 0; i < 10; ++i) {
        x += i;
    }
    if (!libc.strncmp("hello","hello",5)) { correct++; }
    if (libc.strncmp("helo","hello",4) != 0) { correct++; }
    assign();
    runMain();
    test.printResult(correct,tests);
    libc.free(c);
    printf("===\n");
    os.exit(0);
}
