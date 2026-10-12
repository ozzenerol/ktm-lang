import cstr, fs, libc, mem, proc, test;

/* Overloading's errors (v0.6.0): an ambiguous call, a call no overload
 * takes, the same parameters with another return type, an extern "c"
 * function, `&f` with no function type to pick an overload or with one
 * none has, a spread into an overloaded function. Each is one error at
 * the call or declaration. */

#define OE_SRC "./ktm-551-ovl.ktm"
#define OE_OUT "./ktm-551-ovl.out"

u8 *diagnose(u8 *source) {
    proc.shell("rm -f " OE_SRC " " OE_OUT);
    fs.writeAll(OE_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               "-S " OE_SRC " -o /dev/null > " OE_OUT " 2>&1");
    u8 *out = fs.readAll(OE_OUT).take();
    proc.shell("rm -f " OE_SRC " " OE_OUT);
    return out;
}

/* the first error is `want`, at `where` */
bool reports(u8 *source, u8 *want, u8 *where) {
    u8 *out = diagnose(source);
    u8 *first = out ? libc.strstr(out, "error:") : null;
    bool ok = first && libc.strstr(first, want) == first + 7 &&
              libc.strstr(out, where);
    if (!ok) { printf("\n%s\n----\n%s\n", source, out); }
    mem.free(out);
    return ok;
}

i32 main() {
    printf("Test - overloading errors: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (reports("void f(i32 x) { }\nvoid f(i16 x) { }\ni32 main() { f(5); return 0; }\n",
                "the call `f(i64)` is ambiguous: `f(i32)`, `f(i16)` fit as well; "
                "cast an argument to pick one", "ktm-551-ovl.ktm:3:14\n")) { correct++; }
    tests++;
    if (reports("void f(i64 x) { }\nvoid f(u8[] x) { }\ni32 main() { f(2.5, 1); return 0; }\n",
                "no overload of `f` takes (f64, i64); there are `f(i64)`, `f(u8[])`",
                "ktm-551-ovl.ktm:3:14\n")) { correct++; }
    tests++;
    if (reports("struct A { i64 n; };\nvoid A.m(i64 x) { }\nvoid A.m(u8[] s) { }\n"
                "i32 main() { A a; a.m(null); return 0; }\n",
                "no overload of `A.m` takes (", "ktm-551-ovl.ktm:4:21\n")) { correct++; }
    tests++;
    if (reports("void f(i64 x) { }\ni64 f(i64 y) { return 1; }\ni32 main() { return 0; }\n",
                "conflicting types for `f`: `i64 f(i64 y);` does not match the earlier "
                "declaration `void f(i64 x);`", "ktm-551-ovl.ktm:2:5\n")) { correct++; }
    tests++;
    if (reports("extern \"c\" i64 labs(i64 x);\ni64 labs(u8[] s) { return 0; }\n"
                "i32 main() { return 0; }\n",
                "`labs` is an extern \"c\" function: it has one C symbol, so it can't be "
                "overloaded", "ktm-551-ovl.ktm:2:")) { correct++; }
    tests++;
    if (reports("void f(i64 x) { }\nvoid f(u8[] x) { }\ni32 main() { auto p = &f; return 0; }\n",
                "`f` has overloads `f(i64)`, `f(u8[])`: `&f` needs the function type to "
                "pick one, as in `fn(i64) void p = &f;`", "ktm-551-ovl.ktm:3:23\n")) {
        correct++;
    }
    tests++;
    if (reports("void f(i64 x) { }\nvoid f(u8[] x) { }\n"
                "i32 main() { fn(f64) void p = &f; return 0; }\n",
                "no overload of `f` has the type `fn(f64) void`; there are `f(i64)`, `f(u8[])`",
                "ktm-551-ovl.ktm:3:")) { correct++; }
    tests++;
    if (reports("void f(i64 ...a) { }\nvoid f(u8[] s) { }\n"
                "i32 main() { i64[] v = {1, 2}; f(...v); return 0; }\n",
                "`...` can't spread into `f`, which is overloaded", "ktm-551-ovl.ktm:3:")) {
        correct++;
    }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
