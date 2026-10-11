import test;

import fs, libc, mem, proc, str, vec;

/* A definition (or a later prototype) dropped the default arguments of
 * the earlier prototype: `i64 F(i64 *v, i64 n=2);` then
 * `i64 F(i64 *v, i64 n) {...}` made `F(a)` fail with "Unexpected number
 * of arguments 1 in call to F(), expected 2 args". Defaults from the
 * earlier declaration now carry over (as in C++); repeating the same
 * default is fine, a different one is an error. */

#define DEFARGS_FILE "__213_defargs.ktm"
#define DEFARGS_OUT  "__213_defargs.out"
#define DEFARGS_BIN  "__213_defargs.bin"

i64 sum(i64 *v, i64 n=2);
f64 scale(f64 x, f64 by=2.5);
u8 *greet(u8 *name="world");
i64 mid(i64 a, i64 b=10, i64 c=5);
i64 twice(i64 y) { return y * 2; }
i64 apply(i64 x, fn(i64 y) i64 f=&twice);
i64 same(i64 n=4);
i64 twice2(i64 a=1, i64 b=2);
i64 twice2(i64 a, i64 b);          /* a second prototype keeps them */

/* std.ktmh prototypes: redeclaring without the default keeps it */
pub vec.IntVec *IntVecNew(i64 size);
pub u8 *StrTrimStart(u8 *str, i64 str_len, i64 *newLen);

i64 callsBefore()
{// calls before the definition use the prototype, as before
    i64 v[3] = {1, 2, 3};
    return sum(v);
}

i64 sum(i64 *v, i64 n) {
    i64 total = 0;
    for (i64 i = 0; i < n; ++i) { total += v[i]; }
    return total;
}

f64 scale(f64 x, f64 by) { return x * by; }
u8 *greet(u8 *name) { return name; }
i64 mid(i64 a, i64 b, i64 c) { return a * 100 + b * 10 + c; }
i64 apply(i64 x, fn(i64 y) i64 f) { return f(x); }
i64 same(i64 n=4) { return n; }
i64 twice2(i64 a, i64 b) { return a * 10 + b; }

i64 plusOne(i64 y) { return y + 1; }

void cleanupFiles() {
    fs.remove(DEFARGS_FILE);
    fs.remove(DEFARGS_OUT);
    fs.remove(DEFARGS_BIN);
}

/* Compile `source` with ktm; true if the result matches `should_fail`
 * and, when `expected` is given, the output contains it. */
bool compile(u8 *source, bool shouldFail, u8 *expected) {
    cleanupFiles();
    if (!fs.writeAll(DEFARGS_FILE, source, str.len(source),
                                 libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    i64 res = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                                     "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                                     DEFARGS_FILE " -o " DEFARGS_BIN " > " DEFARGS_OUT " 2>&1");
    bool ok = (res != 0) == shouldFail;
    if (ok && expected) {
        u8 *out = fs.readAll(DEFARGS_OUT);
        ok = out && libc.strstr(out, expected) != null;
        if (!ok && out) { printf("%s\n", out); }
        mem.free(out);
    }
    cleanupFiles();
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    i64 v[4] = {1, 2, 3, 4};
    printf("Test - prototype default arguments: ");

    tests++; if (sum(v) == 3 && sum(v, 4) == 10 && callsBefore() == 3) { correct++; }
    else { printf("int default lost\n"); }
    tests++; if (scale(2.0) == 5.0 && scale(2.0, 3.0) == 6.0) { correct++; }
    else { printf("float default lost\n"); }
    tests++; if (!str.cmp(greet(), "world") && !str.cmp(greet("ktm"), "ktm")) { correct++; }
    else { printf("string default lost\n"); }
    tests++; if (mid(1) == 205 && mid(1, 2) == 125 && mid(1, 2, 3) == 123) { correct++; }
    else { printf("second default lost\n"); }
    tests++; if (apply(5) == 10 && apply(5, &plusOne) == 6) { correct++; }
    else { printf("function pointer default lost\n"); }
    tests++; if (same() == 4 && same(7) == 7) { correct++; }
    tests++; if (twice2() == 12 && twice2(3) == 32 && twice2(3, 4) == 34) { correct++; }
    else { printf("second prototype dropped the defaults\n"); }

    vec.IntVec *iv = IntVecNew();
    i64 trimmed_len = 0;
    u8 *t = StrTrimStart(str.dup("  x"), 3, &trimmed_len);
    u8 *t2 = StrTrimStart(str.dup("  y"));
    tests++; if (iv && iv.size == 0 && !str.cmp(t, "x") && !str.cmp(t2, "y") && trimmed_len == 1) { correct++; }
    else { printf("std.ktmh default lost\n"); }
    iv.release();
    mem.free(t);
    mem.free(t2);

    tests++;
    if (compile("i64 F(i64 n=2);\ni64 F(i64 n=3) { return n; }\n"
                            "i32 main() { return F(); }\n", true,
                            "default argument `3` for parameter `n` of `F` does not match")) {
        correct++;
    } else { printf("different default accepted\n"); }

    tests++;
    if (compile("import vec;\n\npub vec.IntVec *IntVecNew(i64 size=64);\n"
                            "i32 main() { IntVecNew(); return 0; }\n", true,
                            "does not match the earlier declaration's `32`")) {
        correct++;
    } else { printf("different std.ktmh default accepted\n"); }

    tests++;
    if (compile("i64 F(i64 n=1+1);\ni64 F(i64 n=2) { return n; }\n"
                            "i32 main() { return F() - 2; }\n", false, null)) {
        correct++;
    } else { printf("equal constant defaults rejected\n"); }

    tests++;
    if (compile("i64 F(i64 a, i64 n=2);\ni64 F(i64 n) { return n; }\n"
                            "i32 main() { return 0; }\n", true, "conflicting types for `F`")) {
        correct++;
    } else { printf("conflicting prototype not reported\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
