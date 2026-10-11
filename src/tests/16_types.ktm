import cstr, test;

i32 main() {
    printf("Test - comma separated declarators & types: ");
    i64 correct = 0, tests = 3;
    u8 *ptr, *ptr2 = "hey", ch;
    ptr = "hello";
    ch = 'f';

    if (!cstr.cmpLen(ptr,"hello",5)) { ++correct; }
    if (!cstr.cmpLen(ptr2,"hey",3)) { ++correct; }
    if (ch == 'f') { ++correct; }

    test.printResult(correct,tests);
    printf("====\n");
    return 0;
}
