import mem, str, test;

i32 main() {
    printf("Test - MemCpy, MemSet: ");
    i64 correct = 0, tests = 4;
    u8 *str1 = mem.alloc(10);
    mem.copy(str1,"hello",5);
    str1[6]='\0';

    u8 *str2 = mem.alloc(10);
    mem.copy(str2,"zooo",4);
    str2[5]='\0';

    if (!str.cmpLen(str1,"hello",5)) { ++correct; }
    if (!str.cmpLen(str2,"zooo",4)) { ++correct; }
    if (str.cmpLen(str1, str2, 4) != 0) { ++correct; }

    mem.set(str1,'z',8);
    mem.copy(str2,str1,8);

    str1[9] = str2[9] = '\0';
    if (!str.cmpLen(str1, str2, 4) != 0) { ++correct; }

    mem.free(str1);
    mem.free(str2);

    test.printResult(correct,tests);
    return 0;
}
