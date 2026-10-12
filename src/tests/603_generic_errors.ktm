import cstr, fs, libc, mem, proc, test;

/* Generics' errors (v0.9.0): a generic struct without its arguments, the
 * wrong number of arguments, arguments that can't be worked out, and an
 * error inside an instance's body, which also says which instance and
 * where it was asked for. */

#define GE_SRC "./ktm-603-gen.ktm"
#define GE_OUT "./ktm-603-gen.out"

u8 *diagnose(u8 *source) {
    proc.shell("rm -f " GE_SRC " " GE_OUT);
    fs.writeAll(GE_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               "-S " GE_SRC " -o /dev/null > " GE_OUT " 2>&1");
    u8 *out = fs.readAll(GE_OUT).take();
    proc.shell("rm -f " GE_SRC " " GE_OUT);
    return out;
}

/* the first error is `want`, and `also` is in the output */
bool reports(u8 *source, u8 *want, u8 *also) {
    u8 *out = diagnose(source);
    u8 *first = out ? libc.strstr(out, "error:") : null;
    bool ok = first && libc.strstr(first, want) == first + 7 && (!also || libc.strstr(out, also));
    if (!ok) { printf("\n%s\n----\n%s\n", source, out); }
    mem.free(out);
    return ok;
}

i32 main() {
    printf("Test - generics errors: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (reports("struct Box<T> { T v; };\ni32 main() { Box b; return 0; }\n",
                "`Box` is generic: write its type arguments, as in `Box<i64>`", "ktm-603-gen.ktm:2:")) {
        correct++;
    }
    tests++;
    if (reports("struct Box<T> { T v; };\ni32 main() { Box<i64, i64> b; return 0; }\n",
                "`Box` takes 1 type argument, got 2", null)) { correct++; }
    tests++;
    if (reports("T one<T>() { return 1; }\ni32 main() { return (i32)one(); }\n",
                "can't work out the type arguments of `one`: nothing says what `T` is; "
                "write them, as in `one<i64>(...)`", null)) { correct++; }
    tests++;
    if (reports("T big<T>(T a, T b) { return a > b ? a : b; }\n"
                "i32 main() { return (i32)big(1, 2.5); }\n",
                "can't work out the type arguments of `big`: `T` is `i64` from one argument "
                "and `f64` from another", null)) { correct++; }
    tests++;
    if (reports("struct P { i64 x; };\nT big<T>(T a, T b) { return a > b ? a : b; }\n"
                "i32 main() { P p = {1}; P q = {2}; big(p, q); return 0; }\n",
                "The `>` operator cannot be applied", "info: in `big<P>`, made here")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
