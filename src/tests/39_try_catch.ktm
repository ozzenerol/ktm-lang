#include "testhelper.ktm"

enum Err { help, aaa, foo, in, inn, out, aAA, bBB, big };

/* try/catch/throw. Each test sets `marker` to a
 * known value and reads `Fs->except_ch` inside the catch handler
 * to verify the thrown u64 made it through unchanged. */

void throwFromHelper() {
    throw(Err.help);
}

void chainedThrowA() {
    throw(Err.aaa);
}

void chainedThrowB() {
    chainedThrowA();
}

i32 main() {
    printf("Test - try/catch/throw: ");
    i64 correct = 0, tests = 8;

    /* 1. Basic: throw fires, catch runs, value preserved. */
    i64 marker1 = 0;
    try {
        throw(Err.foo);
        marker1 = 0xBAD;          /* unreachable */
    } catch (Error e) {
        marker1 = e.code;
    }
    if (marker1 == Err.foo) { correct++; }

    /* 2. No-throw: try body completes, catch skipped. */
    i64 marker2 = 0;
    try {
        marker2 = 1;
    } catch {
        marker2 = 0xBAD;
    }
    if (marker2 == 1) { correct++; }

    /* 3. Throw escapes a function boundary. */
    i64 marker3 = 0;
    try {
        throwFromHelper();
        marker3 = 0xBAD;
    } catch (Error e) {
        marker3 = e.code;
    }
    if (marker3 == Err.help) { correct++; }

    /* 4. Throw escapes two function levels. */
    i64 marker4 = 0;
    try {
        chainedThrowB();
        marker4 = 0xBAD;
    } catch (Error e) {
        marker4 = e.code;
    }
    if (marker4 == Err.aaa) { correct++; }

    /* 5. Nested try: inner catch handles, outer doesn't fire. */
    i64 marker5Inner = 0, marker5Outer = 0;
    try {
        try {
            throw(Err.in);
        } catch (Error e) {
            marker5Inner = e.code;
        }
        /* This statement should run after the inner catch completes. */
        marker5Inner += 1;
    } catch {
        marker5Outer = 0xBAD;
    }
    if (marker5Inner == Err.in + 1 && marker5Outer == 0) { correct++; }

    /* 6. Rethrow from inside catch: inner handler throws a new
     *    value, outer catches it. */
    i64 marker6Outer = 0;
    try {
        try {
            throw(Err.inn);
        } catch {
            throw(Err.out);
        }
    } catch (Error e) {
        marker6Outer = e.code;
    }
    if (marker6Outer == Err.out) { correct++; }

    /* 7. Sequential try blocks: second try is independent of first. */
    i64 marker7a = 0, marker7b = 0;
    try {
        throw(Err.aAA);
    } catch (Error e) {
        marker7a = e.code;
    }
    try {
        throw(Err.bBB);
    } catch (Error e) {
        marker7b = e.code;
    }
    if (marker7a == Err.aAA && marker7b == Err.bBB) { correct++; }

    /* 8. Catch handler with a compound statement body, runs more
     *    than a single expression. */
    i64 marker8 = 0;
    try {
        throw(Err.big);
    } catch (Error e) {
        marker8 = e.code;
        marker8 = marker8 ^ 0xFF;
    }
    if (marker8 == (Err.big ^ 0xFF)) { correct++; }

    PrintResult(correct, tests);
    printf("=====\n");
    return 0;
}
