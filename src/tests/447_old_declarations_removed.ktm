import cstr, fs, libc, mem, proc, test;

/* More old forms removed in ktm syntax v0.2, each an error naming what to
 * write, reported once and parsed on: the unnamed `(...)` with argc and
 * argv, the C function-pointer declarator, the postfix cast, a character
 * printed as a statement and a character as a throw code. C's own `...`
 * (an extern "c" prototype) and fn types with `...` stay. */

#define OD_SRC "./ktm-447-old.ktm"
#define OD_OUT "./ktm-447-old.out"

u8 *diagnose(u8 *source) {
    proc.shell("rm -f " OD_SRC " " OD_OUT);
    fs.writeAll(OD_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               "-S " OD_SRC " -o /dev/null > " OD_OUT " 2>&1");
    u8 *out = fs.readAll(OD_OUT).take();
    proc.shell("rm -f " OD_SRC " " OD_OUT);
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

i32 main() {
    printf("Test - old declarations removed: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (reports("i64 f(i64 n, ...) { return argc; }\nvoid main() { }\n", 1,
                "`...` needs a name: write `...args`", "ktm-447-old.ktm:1:5\n")) { correct++; }
    tests++;
    if (reports("i64 (*fp)(i64);\nvoid main() { }\n", 1,
                "a C function-pointer declarator: write `fn(i64) i64 fp`", "ktm-447-old.ktm:1:5\n")) { correct++; }
    tests++;
    if (reports("void main() { u8 c = 3; i64 x = c(i64); }\n", 1,
                "a postfix cast: write `(i64)` before the value", "ktm-447-old.ktm:1:34\n")) { correct++; }
    tests++;
    if (reports("void main() { 'a'; }\n", 1,
                "a character printed as a statement", "ktm-447-old.ktm:1:15\n")) { correct++; }
    tests++;
    if (reports("void main() { try { throw('x'); } catch { } }\n", 1,
                "a character as the code of `throw`", "ktm-447-old.ktm:1:27\n")) { correct++; }
    /* what stays: C's `...`, fn types with `...`, named varargs */
    tests++;
    if (reports("extern \"c\" i32 open(u8 *path, i32 flags, ...);\n"
                "fn(u8 *, i32, ...) i32 o = &open;\n"
                "i64 sum(...args) { return args.len; }\nvoid main() { }\n", 0, null, null)) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
