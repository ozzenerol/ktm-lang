import cstr, fs, libc, mem, proc, test;

/* fn(...) types, three gaps:
 * - a `*` after the return type is part of it: `fn(u8 *) u8 *` returns
 *   a u8 *. Before, it made a pointer to the function pointer, so a
 *   function returning a pointer could not be written in the fn form.
 *   A pointer to a function pointer now puts the fn type in
 *   parentheses, `(fn(i64) i64) *slot`, in declarations, parameters,
 *   fields, casts, sizeof and typeof, which also print it that way.
 * - parameters can have defaults, `fn(i64 a, i64 b = 10) i64`, filled
 *   in by a call through it as for `i64 (*f)(i64 a, i64 b = 10)`.
 * - `...name` as the first parameter (`fn(...args) i64`) is a type at
 *   the start of a statement, in if/for initialisers, sizeof and
 *   typeof; it was taken for a call. */

#define AT_SRC "./ktm-433-fnret.ktm"
#define AT_OUT "./ktm-433-fnret.out"

u8 *diagnose(u8 *source) {
    proc.shell("rm -f " AT_SRC " " AT_OUT);
    fs.writeAll(AT_SRC, source, cstr.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " AT_SRC " -o /dev/null > " AT_OUT " 2>&1");
    u8 *out = fs.readAll(AT_OUT);
    proc.shell("rm -f " AT_SRC " " AT_OUT);
    return out;
}

/* ktm's output for body has want in it */
bool prints(u8 *body, u8 *want) {
    u8 *src = cstr.format(
"i64 t(i64 x) { return x; }
fn(i64) i64 op = &t;
%s
i32 main() { return 0; }
", body);
    u8 *out = diagnose(src);
    bool ok = out && libc.strstr(out, want) != null;
    if (!ok) { printf("\n%s\n----\n%s\n", body, out); }
    mem.free(out);
    mem.free(src);
    return ok;
}

u8 *dup(u8 *s) { return s + 1; }
void *work(void *a) { return a; }
i64 twice(i64 x) { return x * 2; }
i64 add(i64 a, i64 b) { return a + b; }
i64 sumN(...args) {
    i64 t = 0;
    for (i64 i = 0; i < args.len; i++) { t += args[i]; }
    return t;
}
i64 sumT(i64 ...nums) {
    i64 t = 0;
    for (auto n : nums) { t += n; }
    return t;
}
i32 count(u8 *f, ...args) { return args.len; }
i64 vaFn(...args) { return args.len; }

/* return types, pointers to function pointers */
fn(u8 *) u8 * gDup = &dup;
fn(i64) i64 gOp = &twice;
(fn(i64) i64) *gSlot = &gOp;
struct Box { fn(void *) void * w; (fn(i64) i64) *slot; fn(i64 a, i64 b = 10) i64 k; };
i64 callSlot((fn(i64) i64) *s, i64 x) { return (*s)(x); }
fn(u8 *) u8 * pickDup() { return &dup; }

/* defaults */
fn(i64 a, i64 b = 10) i64 gK = &add;
i64 callK(fn(i64, i64 = 100) i64 k, i64 x) { return k(x); }

/* a parameter named fn is still called, varargs passed on */
i64 fwd(fn(...args) i64 fn_, ...args) { return fn_(...args); }

i32 main() {
    printf("Test - fn types: return pointers, defaults, ...name: ");
    i64 correct = 0, tests = 0;

    /* a `*` after the return type is part of it */
    fn(u8 *) u8 * d = &dup;
    fn(void *) void * w = &work;
    tests++; if (!cstr.cmp(d("xab"), "ab") && !cstr.cmp(gDup("xy"), "y")) { correct++; }
    tests++; if ((i64)w((void *)7) == 7 && pickDup()("zq")[0] == 'q') { correct++; }

    /* (fn(...) R) * is a pointer to a function pointer */
    (fn(i64) i64) *slot = &gOp;
    Box b;
    b.w = &work;
    b.slot = &gOp;
    b.k = &add;
    tests++;
    if ((*slot)(4) == 8 && (*gSlot)(5) == 10 && callSlot(slot, 6) == 12 &&
            (*b.slot)(7) == 14) {
        correct++;
            }
    void *raw = slot;
    tests++;
    if ((*((fn(i64) i64) *)raw)(9) == 18 && (*((fn(i64) i64) *)raw)(10) == 20) {
        correct++;
    }
    tests++;
    if (sizeof((fn(i64) i64) *) == 8 && sizeof(fn(u8 *) u8 *) == 8 && sizeof(Box) == 24) {
        correct++;
    }

    /* typeof prints them as written */
    tests++;
    if (!cstr.cmp(typeof(d), "fn(u8*) u8 *") && !cstr.cmp(typeof(slot), "(fn(i64) i64) *") &&
            !cstr.cmp(typeof(b.slot), "(fn(i64) i64) *") && !cstr.cmp(typeof(gOp), "fn(i64) i64")) {
        correct++;
            } else { printf("\n  %s | %s | %s\n", typeof(d), typeof(slot), typeof(b.slot)); }

    /* defaults: local, global, field, parameter */
    fn(i64 a, i64 b = 10) i64 k = &add;
    tests++;
    if (k(1) == 11 && k(1, 2) == 3 && gK(2) == 12 && b.k(3) == 13 && callK(&add, 4) == 104) {
        correct++;
    }
    tests++;
    if (!cstr.cmp(typeof(k), "fn(i64, i64) i64")) { correct++; }
    else { printf("\n  %s\n", typeof(k)); }

    /* varargs, named or not, typed or not, first or not */
    fn(...args) i64 va = &vaFn;
    fn(...args) i64 sn = &sumN;
    fn(i64 ...) i64 st = &sumT;
    fn(i64 ...nums) i64 st2 = &sumT;
    fn(u8 *, ...args) i32 cnt = &count;
    tests++;
    if (va(1, 2) == 2 && sn(3, 4) == 7 && st(5, 6) == 11 && st2(7, 8) == 15 &&
            cnt("x", 1, 2, 3) == 3 && fwd(&sumN, 1, 2, 3) == 6) {
        correct++;
            }
    i64 seen = 0;
    if (fn(...args) i64 q = &sumN; q(1) == 1) { seen++; }
    for (fn(i64 ...nums) i64 r = &sumT; r(2) == 2; ) { seen++; break; }
    tests++; if (seen == 2) { correct++; }
    tests++;
    if (sizeof(fn(...args) i64) == 8 && sizeof(fn(u8 *, ...args) i32) == 8 &&
            !cstr.cmp(typeof(fn(...args) i64), "fn(...args) i64") &&
            !cstr.cmp(typeof(fn(u8 *, ...args) i32), "fn(u8*, ...args) i32") &&
            !cstr.cmp(typeof(fn(i64 ...nums) i64), "fn(i64 ...) i64") &&
            !cstr.cmp(typeof(fn(...) i64), "fn(...) i64")) {
        correct++;
            }

    /* the `*` belongs to the return type: a function pointer is not a
     * function returning an i64 *, nor a pointer to one */
    tests++;
    if (prints("fn(i64) i64 *bad = &t;", "cannot convert fn(i64) i64 to fn(i64) i64 *")) {
        correct++;
    }
    tests++;
    if (prints("(fn(u8 *) i64) *bad = &op;",
                         "cannot convert (fn(i64) i64) * to (fn(u8*) i64) *")) {
        correct++;
                         }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
