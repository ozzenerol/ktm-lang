import cstr, fs, libc, mem, proc, test;

/* The C declarator of a function returning a function pointer,
 * `i64 (*f())(i64) { ... }`, is an error naming the fn form to write,
 * like the variable one (test 447); it compiled without one. The fn form
 * (a function returning `fn(i64) i64`, or a pointer to one) works. */

#define RD_SRC "./ktm-530-ret.ktm"
#define RD_OUT "./ktm-530-ret.out"

u8 *diagnose(u8 *source) {
    proc.shell("rm -f " RD_SRC " " RD_OUT);
    fs.writeAll(RD_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               "-S " RD_SRC " -o /dev/null > " RD_OUT " 2>&1");
    u8 *out = fs.readAll(RD_OUT).take();
    proc.shell("rm -f " RD_SRC " " RD_OUT);
    return out;
}

i64 count(u8 *s, u8 *what) {
    i64 n = 0;
    while (s && (s = libc.strstr(s, what))) {
        n++;
        s++;
    }
    return n;
}

/* `errors` errors, the first with `want` at `where` */
bool reports(u8 *source, i64 errors, u8 *want, u8 *where) {
    u8 *out = diagnose(source);
    bool ok = out && count(out, "error:") == errors &&
              (!want || (libc.strstr(out, want) && libc.strstr(out, where)));
    if (!ok) { printf("\n%s\n----\n%s\n", source, out); }
    mem.free(out);
    return ok;
}

i64 twice(i64 x) { return x * 2; }

fn(i64 x) i64 gTwice = &twice;

fn(i64 x) i64 handler() { return &twice; }

(fn(i64 x) i64) *handlerSlot() { return &gTwice; }

i32 main() {
    printf("Test - C declarator of a function returning a function pointer: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (reports("i64 twice(i64 x) { return x * 2; }\n"
                "i64 (*getHandler())(i64) { return &twice; }\nvoid main() { }\n", 1,
                "a C function-pointer declarator: write `fn(i64) i64 getHandler(...)`",
                "ktm-530-ret.ktm:2:7\n")) { correct++; }
    tests++;
    if (reports("i64 (**getSlot(bool b))(i64) { return null; }\nvoid main() { }\n", 1,
                "a C function-pointer declarator: write `(fn(i64) i64) * getSlot(...)`",
                "ktm-530-ret.ktm:1:8\n")) { correct++; }
    /* a prototype too, and each declarator reports once */
    tests++;
    if (reports("f64 (*pick(i64 n))(f64);\ni64 (*other())(i64);\nvoid main() { }\n", 2,
                "a C function-pointer declarator: write `fn(f64) f64 pick(...)`",
                "ktm-530-ret.ktm:1:7\n")) { correct++; }
    /* the fn forms compile without an error */
    tests++;
    if (reports("i64 twice(i64 x) { return x * 2; }\n"
                "fn(i64) i64 g = &twice;\n"
                "fn(i64) i64 getHandler() { return &twice; }\n"
                "(fn(i64) i64) *getSlot() { return &g; }\nvoid main() { }\n", 0, null, null)) { correct++; }
    tests++;
    if (handler()(4) == 8 && (*handlerSlot())(5) == 10) { correct++; }
    else { printf("\nthe fn forms don't run\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
