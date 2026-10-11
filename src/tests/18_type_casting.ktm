import mem, test;

/**
 * Type casting is a postfix cast of a functional style. */

struct Foo {
    i32 x;
};

struct Ptr {
    i64 x,y;
    fn(i64 x) i64 fn_;
    void *priv;
};

i64 add2(i64 x) {
    return x + 2;
}

i64 ptrCast(void *ptr)
{ // This shows we've casted from 'void *' to a 'Ptr *'
    return ((Ptr *)ptr).y == 11;
}

i64 i64ToI8Cast(i64 ll)
{ // down cast
    return (u8)ll == 'h'; 
}

i64 i64ToU8Cast(i64 ll)
{ // down cast
    return (u8)ll == 0xFF; 
}

i64 u8ToI64(u8 ch)
{ // Upcast
    return (i64)ch == 99;
}

i64 varadic(...args) {
    for (i64 i = 0; i < args.len; ++i) {
        ((Ptr *)args[i]).x = 10;
    }
    return args.len;
}

bool varadicTest() {
    Ptr *p1 = mem.alloc(sizeof(Ptr)),
            *p2 = mem.alloc(sizeof(Ptr)),
            *p3 = mem.alloc(sizeof(Ptr)),
            *p4 = mem.alloc(sizeof(Ptr));
    varadic(p1,p2,p3,p4);
    return p1.x == 10 &&
                 p2.x == 10 &&
                 p3.x == 10 && 
                 p4.x == 10;
}

bool callingAFunctionPointer(void *ptr)
{ // This is the most amount of casting possible to do
    ((Ptr *)ptr).fn_ = &add2;
    return (i32)((Ptr *)ptr).fn_(5) == 7;
}

bool callingAFunctionPointerPart2(void *ptrArg) {
    Ptr *ptr = (Ptr *)ptrArg;
    ptr.fn_ = &add2;
    return (i32)ptr.fn_(5) == 7;
}

bool castingClassMember(Ptr *p) {
    Foo *foo = (Foo *)p.priv;
    return foo.x == 32;
}

bool castingArray() {
    u64 arr[3] = {1,2,3};
    return (i32)arr[0] == 1 && 
                 (i16)arr[1] == 2 && 
                 (i8)arr[2] == 3;
}

i32 main() {
    printf("Test - Type casting:\n");
    i64 correct = 0, tests = 9;
    Ptr *p = mem.alloc(sizeof(Ptr));
    p.x = 10;
    p.y = 11;

    if (ptrCast(p)) { ++correct; }
    /* hello in hexidecimal */
    if (i64ToI8Cast(0x6F6C6C6568)) { ++correct; }
    if (u8ToI64(99)) { ++correct; }
    if (i64ToU8Cast(0xAAFF)) { ++correct; }
    if (varadicTest()) { ++correct; }
    if (callingAFunctionPointer(p)) { ++correct; }
    if (callingAFunctionPointerPart2(p)) { ++correct; }
    if (castingArray()) { ++correct; }
    Foo *foo = mem.alloc(sizeof(Foo));
    foo.x = 32;
    p.priv = foo;
    if (castingClassMember(p)) { ++correct; }
    mem.free(foo);

    test.printResult(correct,tests);
    printf("=====\n");
    return 0;
}
