import os, test;

/* The suite compiles AOT, so #ifaot branches must be taken and #ifjit
 * branches skipped. The JIT side is covered by run_jit compiling the
 * same file expectations inverted would break these asserts there, so
 * modeFn() returns which branch was compiled in and the assert adapts
 * via __KTM_AOT__. */

i64 correct = 0;
i64 tests = 3;

i64 modeFn() {
#ifjit
    return 1;
#else
    return 2;
#endif
}

i64 mode2() {
#ifaot
    return 2;
#else
    return 1;
#endif
}

#ifaot
i64 aotOnly = 42;
#endif
#ifjit
i64 aotOnly = 0;
#endif

void main() {
    printf("Test - #ifjit / #ifaot: ");
#ifdef __KTM_AOT__
    if (modeFn() == 2) { correct++; }
    if (mode2() == 2) { correct++; }
    if (aotOnly == 42) { correct++; }
#else
    if (modeFn() == 1) { correct++; }
    if (mode2() == 1) { correct++; }
    if (aotOnly == 0) { correct++; }
#endif
    test.printResult(correct, tests);
    printf("===\n");
    os.exit(0);
}
