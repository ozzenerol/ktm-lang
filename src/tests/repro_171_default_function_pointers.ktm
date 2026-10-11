import cstr, mem, test;

void FN() {
    printf("FN()\n");
}

u8 *printMessage(u8 *msg,
                                 i64 i,
                                 fn() void fn_ = null,
                                 i64 N) {
    if (fn_) { fn_(); }
    u8 *buffer = cstr.format("%s i=%d N=%d\n",msg, i, N);
    return buffer;
}

i32 main() {
    printf("Repro 171 (Default function pointer arguments): ");
    i64 correct = 0;
    i64 total = 1;

    u8 *msg = printMessage("hello!",24,,4);

    if (!cstr.cmp(msg, "hello! i=24 N=4\n")) {
        correct++;
    }
    test.printResult(correct,total);
    mem.free(msg);

    printf("====\n");
    return 0;
}
