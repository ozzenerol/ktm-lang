import test;

import str;

i32 main() {
    printf("Test - comma separated declarators & types: ");
    i64 correct = 0, tests = 3;
    u8 *ptr, *ptr2 = "hey", ch;
    ptr = "hello";
    ch = 'f';

    if (!str.cmpLen(ptr,"hello",5)) { ++correct; }
    if (!str.cmpLen(ptr2,"hey",3)) { ++correct; }
    if (ch == 'f') { ++correct; }

    test.printResult(correct,tests);
    printf("====\n");
    return 0;
}
