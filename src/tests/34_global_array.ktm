import cstr, test;

u8 *htmlAttrs[] = {
    "invalid",
    "html",
    "div",
    "span",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "nav",
    "strong",
    "italic"
};

bool testStaticArray() {
    i64 tests = 4, correct = 0;
    static u8 *arr[] = {
        "f1",
        "f2",
        "dogs and cats are fun"
    };

    i64 len = sizeof(arr)/sizeof(arr[0]);

    if (len == 3) { correct++; }
    if (!cstr.cmp(arr[0],"f1")) { correct++; }
    if (!cstr.cmp(arr[1],"f2")) { correct++; }
    if (!cstr.cmp(arr[2],"dogs and cats are fun")) { correct++; }

    return tests == correct;
}

bool testGlobalArray() {
    i64 tests = 13, correct = 0;
    i64 len = sizeof(htmlAttrs)/sizeof(htmlAttrs[0]);

    if (len == 12) { correct++; }
    if (!cstr.cmp(htmlAttrs[0], "invalid")) { correct++; }
    if (!cstr.cmp(htmlAttrs[1], "html")) { correct++; }
    if (!cstr.cmp(htmlAttrs[2], "div")) { correct++; }
    if (!cstr.cmp(htmlAttrs[3], "span")) { correct++; }
    if (!cstr.cmp(htmlAttrs[4], "h1")) { correct++; }
    if (!cstr.cmp(htmlAttrs[5], "h2")) { correct++; }
    if (!cstr.cmp(htmlAttrs[6], "h3")) { correct++; }
    if (!cstr.cmp(htmlAttrs[7], "h4")) { correct++; }
    if (!cstr.cmp(htmlAttrs[8], "h5")) { correct++; }
    if (!cstr.cmp(htmlAttrs[9], "h6")) { correct++; }
    if (!cstr.cmp(htmlAttrs[10], "nav")) { correct++; }
    if (!cstr.cmp(htmlAttrs[11], "strong")) { correct++; }
    if (!cstr.cmp(htmlAttrs[12], "italic")) { correct++; }
    return correct == tests;
}

i32 main() {
    printf("Test - Global and static array\n");
    i64 tests = 2, correct = 0;
    if (testStaticArray()) { correct++; }
    if (testGlobalArray()) { correct++; }
    test.printResult(correct,tests);
    printf("====\n");
    return 0;
}
