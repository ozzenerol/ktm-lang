import test;

import fs, libc, mem, proc, str;

/* ktm syntax v0.2: named variadic parameters. `T ...name` takes zero or
 * more extra arguments, each checked and converted like an argument of
 * type T; `...name` takes them untyped, as 64-bit slots like the old
 * argv. Inside, `name.len` is the count, `name[i]` element i (of type
 * T, `&name[i]` works) and `for (auto v : name)` iterates; `name` can't be
 * assigned. `...name` as a call's extra argument passes them on. The
 * function has no implicit argc/argv. The old `(...)` keeps working. */

i64 sum(i64 ...nums) {
    i64 t = 0;
    for (auto n : nums) { t += n; }
    return t;
}

f64 avg(f64 ...xs) {
    if (!xs.len) { return 0; }
    f64 t = 0;
    for (i64 i = 0; i < xs.len; i++) { t += xs[i]; }
    return t / xs.len;
}

i64 totalLen(u8 *...strs) {
    i64 t = 0;
    for (auto s : strs) { t += str.len(s); }
    return t;
}

i64 narrow(i32 ...v) {
    i64 t = 0;
    for (auto x : v) { t += x; }
    return t;
}

f64 floats(f32 ...v) {
    f64 t = 0;
    for (auto x : v) { t += x; }
    return t;
}

/* untyped: the slots of the old argv, read with a postfix cast */
u8 gBuf[128];
i64 untyped(u8 *tag, ...args) {
    str.formatTo(gBuf, "%s:%d", tag, args.len);
    if (args.len >= 3) {
        str.formatTo(gBuf + str.len(gBuf), ":%d:%.2f:%s", args[0], (f64)args[1], (u8 *)args[2]);
    }
    return args.len;
}

/* a fixed and a default parameter before */
i64 withDefault(i64 a, i64 b = 10, i64 ...rest) {
    return a + b + sum(...rest) * 100 + rest.len * 10000;
}

/* spread */
i64 total(i64 ...nums) {
    return sum(...nums);
}

/* &name[i] and writing an element */
i64 doubled(i64 ...nums) {
    for (i64 i = 0; i < nums.len; i++) {
        i64 *p = &nums[i];
        *p *= 2;
    }
    return sum(...nums);
}

/* recursion: each call has its own extras */
i64 Depth(i64 n, i64 ...extra) {
    if (n == 0) { return sum(...extra); }
    return Depth(n - 1, n, n * 10) + extra.len;
}

/* no implicit argc / argv: a parameter of that name is not shadowed */
i64 argcParam(i64 argc, i64 ...rest) {
    return argc * 100 + rest.len;
}

#define VA_SRC "./ktm-420-varargs.ktm"
#define VA_OUT "./ktm-420-varargs.out"

/* `source` fails to compile with exactly one error, `want` */
bool fails(u8 *source, u8 *want) {
    proc.shell("rm -f " VA_SRC " " VA_OUT);
    fs.writeAll(VA_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " VA_SRC " -o /dev/null > " VA_OUT " 2>&1");
    u8 *out = fs.readAll(VA_OUT);
    proc.shell("rm -f " VA_SRC " " VA_OUT);
    i64 errors = 0;
    u8 *p = out;
    while (p && (p = libc.strstr(p, "error:"))) {
        errors++;
        p++;
    }
    bool ok = out && errors == 1 && libc.strstr(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", source, out); }
    mem.free(out);
    return ok;
}

/* through fn(...) types (ktm syntax v0.2's Y4) */
i64 apply(fn(i64 ...) i64 f) {
    return f(1, 2, 3);
}

/* a call at file scope */
i64 gFileScope;

/* untyped extras are 64-bit slots: a narrow literal fills its slot */
i64 slot(i64 i, ...args) {
    return args[i];
}

i32 main() {
    gFileScope = sum(4, 5, 6);
    printf("Test - syntax: named varargs: ");
    i64 correct = 0, tests = 0;

    /* zero, one and many */
    tests++; if (sum() == 0 && sum(7) == 7 && sum(1, 2, 3, 4) == 10) { correct++; }
    /* typed: converted to the element type */
    tests++; if (avg() == 0 && avg(1, 2.5, 3) == 6.5 / 3) { correct++; }
    tests++; if (sum(1.9, 2) == 3) { correct++; }
    tests++; if (totalLen("ab", "cde", "") == 5 && totalLen() == 0) { correct++; }
    tests++; if (narrow(1, 2, -3, 100) == 100) { correct++; }
    tests++; if (floats(1.5, 2, 0.25) == 3.75) { correct++; }
    /* untyped */
    tests++;
    if (untyped("t") == 0 && !str.cmp(gBuf, "t:0") &&
            untyped("u", 42, 2.5, "s") == 3 && !str.cmp(gBuf, "u:3:42:2.50:s")) {
        correct++;
            }
    /* a default before the extras */
    tests++;
    if (withDefault(1) == 11 && withDefault(1, 2) == 3 &&
            withDefault(1, 2, 3, 4) == 1 + 2 + 700 + 20000) {
        correct++;
            }
    /* spread, nested calls, an element written through &name[i] */
    tests++; if (total() == 0 && total(4, 5, 6) == 15) { correct++; }
    tests++; if (sum(sum(1, 2), sum(), sum(3)) == 6) { correct++; }
    tests++; if (doubled(1, 2, 3) == 12) { correct++; }
    tests++; if (Depth(3) == 15) { correct++; }
    tests++; if (argcParam(3, 9, 9) == 302) { correct++; }
    /* through a function pointer */
    fn(i64 ...n) i64 fp = &sum;
    tests++; if (fp() == 0 && fp(10, 20, 30) == 60) { correct++; }
    fn(i64 ...) i64 ft = &sum;
    fn(u8 *, ...args) i64 fu = &untyped;
    fn(f64 ...) f64 fa = &avg;
    tests++;
    if (ft() == 0 && ft(4, 5) == 9 && fu("f", 1, 2) == 2 && fa(1, 3) == 2 &&
            apply(&sum) == 6 && apply(&total) == 6) {
        correct++;
            }
    tests++;
    if (!str.cmp(typeof(ft), "fn(i64 ...) i64") && !str.cmp(typeof(fa), "fn(f64 ...) f64") &&
            !str.cmp(typeof(fp), "fn(i64 ...) i64")) {
        correct++;
            } else { printf("\n  %s | %s | %s\n", typeof(ft), typeof(fa), typeof(fp)); }
    /* in a loop: the call site's array is reused each time */
    i64 acc = 0;
    for (i64 k = 0; k < 5; k++) { acc += sum(k, k, k); }
    tests++; if (acc == 30) { correct++; }
    tests++; if (gFileScope == 15) { correct++; }
    u8 b200 = 200;
    tests++;
    if (slot(0, true) == 1 && slot(1, 'x', 'y') == 'y' && slot(0, (i8)-3) == -3 &&
            slot(0, b200) == 200 && slot(1, false, (u8)7) == 7) { correct++; }
    /* the old form is unchanged */
    tests++; if (!str.cmp(str.format("%d-%s", 5, "x"), "5-x")) { correct++; }

    /* compile errors */
    tests++;
    if (fails("i64 F(i64 ...n, i64 x) { return 0; }\n",
                        "`...n` must be the last parameter")) { correct++; }
    tests++;
    if (fails("i64 F(i64 ...) { return 0; }\n",
                        "a variadic parameter needs a name")) { correct++; }
    tests++;
    if (fails("i64 S(i64 ...n) { return 0; }\ni64 G() { return S(1, \"x\"); }\n",
                        "argument 2 of `S`: expected i64, got u8[2]")) { correct++; }
    tests++;
    if (fails("i64 S(i64 ...n) { return 0; }\ni64 G(f64 ...m) { return S(...m); }\n",
                        "cannot spread `...m` (f64) into `S`, whose extra arguments are i64")) { correct++; }
    tests++;
    if (fails("i64 S(i64 ...n) { n = null; return 0; }\n",
                        "cannot assign to the variadic parameter 'n'")) { correct++; }
    tests++;
    if (fails("struct P { i64 a; };\ni64 S(P ...n) { return 0; }\n",
                        "the elements of `...n` must be numbers or pointers")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
