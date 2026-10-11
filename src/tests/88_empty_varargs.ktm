import test;

import mem, str;

/* A ktm `...` function always gets the implicit argc/argv, but a call
 * that passed no extra arguments (`Count("a")`) didn't pass argc at all,
 * so the callee read whatever was left in that register or stack slot:
 * Shlurp("printf '%%s' ''") formatted with a garbage argc and argv. */

i64 count(u8 *fmt, ...args) {
    return args.len;
}

i64 sum(u8 *fmt, ...args) {
    i64 i, total = 0;
    for (i = 0; i < args.len; i++) { total += args[i]; }
    return total;
}

/* argc comes after the default arguments that were filled in */
i64 def(i64 a, i64 b = 10, ...args) {
    return a + b + args.len * 1000;
}

i64 allDefault(i64 a = 1, ...args) {
    return a + args.len * 1000;
}

/* As Shlurp and Sh do: use fmt as-is when there is nothing to format */
u8 *format(u8 *fmt, ...args) {
    if (args.len == 0) { return str.dup(fmt); }
    return str.formatArgs(null, fmt, args.len, args);
}

/* Leaves a non-zero value where a following call's argc would go */
i64 junk(u8 *a, i64 b, i64 c, i64 d) {
    return b + c + d;
}

i32 main() {
    printf("Test - calls with no variadic arguments: ");
    i64 tests = 11, correct = 0;
    u8 *s;

    junk("x", 77, 88, 99);
    if (count("a") == 0) { correct++; }
    else { printf("Failed - argc with no arguments\n"); }
    if (count("a", 5) == 1) { correct++; }
    else { printf("Failed - argc with one argument\n"); }
    if (count("a", 5, 6, 7) == 3) { correct++; }
    else { printf("Failed - argc with three arguments\n"); }

    junk("x", 77, 88, 99);
    if (sum("a") == 0) { correct++; }
    else { printf("Failed - argv loop with no arguments\n"); }
    if (sum("a", 1, 2, 3) == 6) { correct++; }
    else { printf("Failed - argv loop with arguments\n"); }

    if (def(1) == 11) { correct++; }
    else { printf("Failed - default argument, no variadic arguments\n"); }
    if (def(1, 2) == 3) { correct++; }
    else { printf("Failed - explicit argument, no variadic arguments\n"); }
    if (def(1, 2, 3) == 1003) { correct++; }
    else { printf("Failed - default argument with a variadic argument\n"); }
    if (allDefault() == 1 && allDefault(5, 6) == 1005) { correct++; }
    else { printf("Failed - all-default variadic function\n"); }

    junk("x", 77, 88, 99);
    s = format("keep %s and 50%%");
    if (!str.cmp(s, "keep %s and 50%%")) { correct++; }
    else { printf("Failed - format string used as-is: '%s'\n", s); }
    mem.free(s);
    s = format("got %s", "it");
    if (!str.cmp(s, "got it")) { correct++; }
    else { printf("Failed - format string with an argument: '%s'\n", s); }
    mem.free(s);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
