import coro, test;

/* coro.inLoop (v0.20.0): false on a plain thread and in a coroutine of
 * coro.spawn, true in a loop coroutine of coro.go. */

i64 gCorrect = 0, gTests = 0;
bool gInGo, gInNew;

void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

void *inGo(void *arg) { gInGo = coro.inLoop(); return null; }

void *inNew(void *arg) { gInNew = coro.inLoop(); return null; }

i32 main() {
    printf("Test - coro.inLoop: ");
    check(!coro.inLoop(), "false outside a coroutine");
    coro.go(&inGo);
    coro.run();
    check(gInGo, "true in coro.go");
    coro.Coroutine *c = coro.spawn(&inNew);
    gInNew = true;
    coro.next(c);
    coro.release(c);
    check(!gInNew, "false in a plain coroutine");
    check(!coro.inLoop(), "false after the loop");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
