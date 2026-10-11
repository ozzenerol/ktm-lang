#include "testhelper.ktm"

/* argc and the argv slots shared one type tagged as a variadic slot,
 * so converting argc to a float reinterpreted its bits like
 * `argv[i](f64)` does, instead of converting the count: 0.0. */

f64 retArgc(...args) { return args.len; }
f64 assignArgc(...args) { f64 f = args.len; return f; }
f64 mulArgc(...args) { return args.len * 1.5; }
f64 castArgc(...args) { return (f64)args.len; }
f64 half(f64 x) { return x / 2.0; }
f64 passArgc(...args) { return half(args.len); }
bool cmpArgc(...args) { f64 two = 2.0; return args.len == two; }
f64 sumF64(...args) {
    f64 sum = 0.0;
    i64 i;
    for (i = 0; i < args.len; i++) {
        sum += (f64)args[i];
    }
    return sum;
}
i64 intArgc(...args) { return args.len; }

i32 main() {
    i64 correct = 0, tests = 10;

    printf("Test - argc converted to a float: ");

    if (retArgc(1, 2) == 2.0) { correct++; }
    if (retArgc() == 0.0) { correct++; }
    if (assignArgc(1, 2, 3) == 3.0) { correct++; }
    if (mulArgc(7, 8) == 3.0) { correct++; }
    if (castArgc(1, 2, 3, 4) == 4.0) { correct++; }
    if (passArgc(1, 2, 3, 4, 5, 6) == 3.0) { correct++; }
    if (cmpArgc(9, 9)) { correct++; }
    /* the argv slots still hold the f64 bits */
    if (sumF64(1.5, 2.25) == 3.75) { correct++; }
    if (intArgc(1, 2, 3) == 3) { correct++; }
    if (retArgc(1, 2, 3, 4, 5, 6, 7, 8, 9, 10) == 10.0) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
