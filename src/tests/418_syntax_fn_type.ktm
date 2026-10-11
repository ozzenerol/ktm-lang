import fs, libc, mem, proc, str, test;

/* ktm syntax v0.2: `fn ( [type {, type}] [, ...] ) RetType` is the new way
 * to write a function pointer type: `fn(i64) i64` is exactly the C
 * declarator `i64 (*)(i64)`, same size, same calls, same argument checks.
 * `*` after the whole construct makes a pointer to it, not to RetType.
 * `fn` is a CONTEXTUAL keyword (old code uses it as a plain identifier):
 * it starts a type only where a type can start, and only when followed by
 * `(` with a parameter list that is empty, `...`-only or starts with a
 * type name, and (checked past the `)` only for the empty/`...`-only
 * cases, where the `)` is right there) a type follows the `)`. See
 * parseFnStartsType in src/prslib.c. */

i64 twice(i64 x) { return x * 2; }
i64 addOne(i64 x) { return x + 1; }

/* old C declarator, kept working */
fn(i64) i64 gOldOp = &twice;

/* the new form: a variable */
fn(i64) i64 gNewOp = &twice;

/* an array of them */
fn(i64, u8 *) void gHandlers[4];
void handler0(i64 n, u8 *s) { printf("h0 %d %s\n", n, s); }

/* a struct field */
struct Server { fn(i32) void onAccept; };
void onAcceptFn(i32 fd) { printf("accepted %d\n", fd); }

/* a function returning a function pointer */
fn(i64) i64 pick(bool sq) {
    if (sq) { return &twice; }
    return &addOne;
}

/* a parameter */
i64 apply(fn(i64) i64 f, i64 x) { return f(x); }

/* nested: a function taking an fn-type and returning an fn-type */
fn(i64) i64 composeImpl(fn(i64) i64 g) { return &twice; }
fn(fn(i64) i64) fn(i64) i64 gCompose = &composeImpl;

/* no parameters */
fn() void gTick;
void tick() { printf("tick\n"); }

/* varargs, with and without fixed parameters first; the call path must
 * thread the varargs through an fn(...) type the same way it does the
 * old declarator, not just set a flag and lose it */
fn(u8 *, ...args) i32 gLogger;
i64 sum(...args) {
    i64 total = 0;
    for (i64 i = 0; i < args.len; i++) { total += (i64)args[i]; }
    return total;
}
i64 sumFrom(i64 n, ...args) {
    i64 total = 0;
    for (i64 i = 0; i < n; i++) { total += (i64)args[i]; }
    return total;
}
fn(...args) i64 gSum = &sum;
fn(i64, ...args) i64 gSumFrom = &sumFrom;

/* used below: a call through a variable named `fn` with an enum member
 * argument must not be read as a one-parameter type, since `Name.member`
 * is an expression (ktm syntax v0.2's Y6), not a type. Declared only
 * where it is used (not as a global): once `fn` names a variable in
 * scope, that shadows `fn(...)` as a type in the same scope, same as a
 * C variable shadowing a type of the same name, so it must not leak
 * into the earlier cast test, which still wants `fn` as a type name. */
enum Color { red, green, blue };
i64 showColor(Color c) { return (i64)c; }

#define AT_SRC "./ktm-418-fn.ktm"
#define AT_OUT "./ktm-418-fn.out"

/* Compile `source`, which must FAIL to compile, and return ktm's output. */
u8 *diagnose(u8 *source) {
    proc.shell("rm -f " AT_SRC " " AT_OUT);
    fs.writeAll(AT_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " AT_SRC " -o /dev/null > " AT_OUT " 2>&1");
    u8 *out = fs.readAll(AT_OUT);
    proc.shell("rm -f " AT_SRC " " AT_OUT);
    return out;
}

/* `body`, wrapped as a whole program, must fail to compile with `want`
 * somewhere in ktm's output. */
bool fails(u8 *body, u8 *want) {
    u8 *src = str.format("%s\ni32 main() { return 0; }\n", body);
    u8 *out = diagnose(src);
    bool ok = out && libc.strstr(out, "error:") && libc.strstr(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", body, out); }
    mem.free(out);
    mem.free(src);
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - fn(...) function types: ");

    /* a variable of the new form, called */
    tests++;
    if (gNewOp(5) == 10) { correct++; }
    else { printf("\n  fn(...) variable, called\n"); }

    /* the old declarator form still works */
    tests++;
    if (gOldOp(5) == 10) { correct++; }
    else { printf("\n  old declarator form still works\n"); }

    /* equality with the old declarator form: no cast either way, same
     * calls, same argument checks (test 413) */
    tests++;
    fn(i64) i64 viaOld = gOldOp;
    fn(i64) i64 viaNew = gNewOp;
    if (viaOld(3) == 6 && viaNew(3) == 6 &&
            apply(gOldOp, 4) == 8 && apply(gNewOp, 4) == 8) {
        correct++;
            } else { printf("\n  fn(...) and the old declarator are the same type\n"); }

    /* an array of fn(...) */
    tests++;
    gHandlers[0] = &handler0;
    gHandlers[0](7, "x");
    if (gHandlers[0] == &handler0) { correct++; }
    else { printf("\n  array of fn(...)\n"); }

    /* a struct field of fn(...) type */
    tests++;
    Server srv;
    srv.onAccept = &onAcceptFn;
    srv.onAccept(9);
    if (srv.onAccept == &onAcceptFn) { correct++; }
    else { printf("\n  struct field of fn(...) type\n"); }

    /* varargs through fn(...), with and without a fixed parameter first */
    tests++;
    if (gSum(1, 2, 3) == 6 && gSumFrom(2, 10, 20) == 30) { correct++; }
    else { printf("\n  varargs through fn(...)\n"); }

    /* a function returning fn(...), called through the result */
    tests++;
    fn(i64) i64 p1 = pick(true);
    fn(i64) i64 p2 = pick(false);
    if (p1(5) == 10 && p2(5) == 6) { correct++; }
    else { printf("\n  function returning fn(...)\n"); }

    /* a parameter of fn(...) type */
    tests++;
    if (apply(&twice, 6) == 12) { correct++; }
    else { printf("\n  fn(...) parameter\n"); }

    /* nested fn(...) types: a parameter and a return type */
    tests++;
    fn(i64) i64 composed = gCompose(&twice);
    if (composed(5) == 10) { correct++; }
    else { printf("\n  nested fn(...) types\n"); }

    /* no parameters */
    tests++;
    gTick = &tick;
    gTick();
    if (gTick == &tick) { correct++; }
    else { printf("\n  fn() with no parameters\n"); }

    /* varargs */
    tests++;
    if (sizeof(gLogger) == 8) { correct++; }
    else { printf("\n  fn(..., ...) varargs\n"); }

    /* pointer to a function pointer: the fn type in parentheses (a `*`
     * after the return type is part of it, test 433) */
    tests++;
    (fn(i64) i64) *slot = &gNewOp;
    if ((*slot)(8) == 16) { correct++; }
    else { printf("\n  pointer to a function pointer\n"); }

    /* a cast */
    tests++;
    auto casted = (fn(i64) i64)gOldOp;
    if (casted(9) == 18) { correct++; }
    else { printf("\n  cast to fn(...)\n"); }

    /* sizeof is 8, comparisons with null work */
    tests++;
    fn(i64) i64 none = null;
    if (sizeof(fn(i64) i64) == 8 && none == null) { correct++; }
    else { printf("\n  sizeof and null\n"); }

    /* typeof prints the new form, for both the new and old declarator */
    tests++;
    if (!str.cmp(typeof(gNewOp), "fn(i64) i64") &&
            !str.cmp(typeof(gOldOp), "fn(i64) i64")) {
        correct++;
            } else { printf("\n  typeof prints the new form\n"); }

    /* `fn` stays a plain identifier everywhere it was one before */
    tests++;
    fn(i64) i64 fn_ = &twice;
    fn_ = &addOne;
    if (fn_(5) == 6) { correct++; }
    else { printf("\n  fn as a plain identifier (old code)\n"); }

    /* a call through a variable named `fn` with an enum member argument:
     * a call, not a one-parameter `fn(...)` type, at statement start, in
     * an expression and in an if condition (its own block: a fresh `fn`,
     * shadowing the outer one, of a different parameter type) */
    tests++;
    {
        fn(Color) i64 fn_ = &showColor;
        fn_(Color.blue);
        i64 r = fn_(Color.green) + 1;
        bool ok = false;
        if (fn_(Color.red) == 0) { ok = true; }
        if (r == 2 && ok) { correct++; }
        else { printf("\n  fn as a variable called with an enum member argument\n"); }
    }

    /* compile errors */

    tests++;
    if (fails("fn(i64) x;", "return type")) { correct++; }
    else { printf("\n  fn(...) with no return type\n"); }

    tests++;
    if (fails("fn i64 x;", "`(`")) { correct++; }
    else { printf("\n  fn without parens\n"); }

    /* the same, as a statement inside a function body: must not fall
     * through to reading `fn` as an expression and reporting a confusing
     * error on an unrelated later token */
    tests++;
    if (fails("i32 g() { fn i64 op; return 0; }", "`(`")) { correct++; }
    else { printf("\n  fn without parens, as a statement\n"); }

    tests++;
    if (fails("fn(i64 x;", "`)`")) { correct++; }
    else { printf("\n  fn(...) missing a closing paren\n"); }

    /* an fn(...) parameter is checked like any function pointer parameter
     * (test 413): an integer argument warns, same as the old declarator
     * form would, not a hard error */
    tests++;
    {
        u8 *src = str.format(
            "i32 Take(fn(i64) i64 f) { return 0; }\n"
            "i32 g() { return Take(2); }\n"
            "i32 main() { return g(); }\n");
        u8 *out = diagnose(src);
        if (out && libc.strstr(out, "warning:") &&
                libc.strstr(out, "makes a pointer from an integer without a cast")) {
            correct++;
                } else { printf("\n  an integer argument through an fn(...) parameter\n%s\n", out); }
        mem.free(out);
        mem.free(src);
    }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
