import libc, mem, test;

extern "c" i64 strcmp(u8 *p,u8*p2);

struct Example {
    fn(u8 *name) u8 *fn_;
};

u8 *identity(u8 *chars) {
    return chars;
}

bool test2() {
    i64 correct, tests;
    Example *e = mem.alloc(sizeof(Example));
    correct = 0;
    tests = 4;

    e.fn_ = &identity;

    fn(u8 *arg) u8 *fn_;
    fn(u8 *arg) u8 *fn2;
    fn(u8 *arg) u8 *fn3 = &identity;
    fn_ = &identity;
    fn2 = fn_;

    if (!libc.strcmp(fn_("fn->hello"), "fn->hello")) { correct++; }
    if (!libc.strcmp(fn2("fn2->hello"), "fn2->hello")) { correct++; }
    if (!libc.strcmp(fn3("fn3->hello"), "fn3->hello")) { correct++; }
    if (!libc.strcmp(e.fn_("class->hello"), "class->hello")) { correct++; }
    mem.free(e);

    return correct == tests;
}

bool test3() {
    i64 correct, tests;
    Example *e = mem.alloc(sizeof(Example));
    fn(u8 *arg) u8 *fn_;
    fn(u8 *arg) u8 *fn2;
    fn(u8 *arg) u8 *fn3;

    fn_ = e.fn_ = fn2 = fn3 = &identity;
    correct = 0;
    tests = 4;

    if (!libc.strcmp(fn_(" fn->hello"), " fn->hello")) { correct++; }
    if (!libc.strcmp(fn2(" fn2->hello"), " fn2->hello")) { correct++; }
    if (!libc.strcmp(fn3(" fn3->hello"), " fn3->hello")) { correct++; }
    if (!libc.strcmp(e.fn_(" class->hello"), " class->hello")) { correct++; }
    mem.free(e);

    return correct == tests;
}

bool test4()
{ // You need to add a '&'
    fn(u8 *arg) u8 *fn_ = &identity;
    if (!libc.strcmp(fn_("hey"),"hey")) {
        return true;
    }
    return false;
}

bool test5()
{ // You need to add a '&'
    fn(u8 *arg) u8 *fn_ = &identity;
    auto ptrArg = fn_;
    if (!libc.strcmp(ptrArg("hey"),"hey")) {
        return true;
    }
    return false;
}

i64 add(i64 x, i64 y) {
    return x + y;
}

i64 doMaths(fn(i64 a, i64 b) i64 mathfunc, i64 x, i64 y) {
    return mathfunc(x,y);
}

i32 main() {
    printf("Test - Function pointers: ");
    i64 correct = 0, tests = 5;
    i64 i = doMaths(&add,10,1);

    if (i == 11) { correct++; }
    if (test2()) { correct++; }
    if (test3()) { correct++; }
    if (test4()) { correct++; }
    if (test5()) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
