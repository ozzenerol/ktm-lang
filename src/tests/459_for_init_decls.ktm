import fs, libc, proc, str, test;

/* `for (i64 i = 0, j = 1; ...)`: several declarations in a for loop's
 * init (j "has not been defined" before), each with its own `*`s as in
 * C, and several steps (`i++, j--`). */

#define SRC_FILE "__459_src.ktm"

u8 *runKtm(u8 *src) {
    fs.writeAll(SRC_FILE, src, str.len(src));
    return proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                            "-jit " SRC_FILE " 2>&1");
}

i32 main() {
    i64 correct = 0, tests = 6;
    printf("Test - several declarations in a for init: ");

    i64 sum = 0;
    for (i64 i = 0, j = 10; i < 3; i++, j--) {
        sum += i * 100 + j;
    }
    if (sum == 327) { correct++; }
    else { printf("\n  i, j: %d\n", sum); }

    i64 arr[3] = {7, 8, 9};
    i64 got = 0;
    for (i64 *p = arr, *end = arr + 3, n = 1; p < end; p++, n *= 10) {
        got += *p * n;
    }
    if (got == 987) { correct++; }
    else { printf("\n  pointers: %d\n", got); }

    u8 *types = null;
    for (auto a = 1, b = 2.5; a < 2; a++) {
        types = str.format("%s %s %.1f", typeof(a), typeof(b), b + a);
    }
    if (types && !str.cmp(types, "i64 f64 3.5")) { correct++; }
    else { printf("\n  auto: [%s]\n", types); }

    i64 later = 0;
    for (i64 k = 0, m; k < 1; k++) {
        m = 4;
        later = k + m;
    }
    if (later == 4) { correct++; }
    else { printf("\n  no initialiser: %d\n", later); }

    u8 *out = runKtm("i32 main() { for (i64 i = 0, i = 1; i < 2; i++) {} return 0; }\n");
    if (out && libc.strstr(out, "variable `i` already declared")) { correct++; }
    else { printf("\n  redeclared: [%s]\n", out); }
    out = runKtm("i32 main() { for (i64 x = 0, 5; x < 1; x++) {} return 0; }\n");
    if (out && libc.strstr(out, "expected an identifier, got `5`")) { correct++; }
    else { printf("\n  not a name: [%s]\n", out); }
    fs.remove(SRC_FILE);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
