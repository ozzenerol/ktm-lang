import coro, fs, libc, mem, proc, str, test;

enum Err { oops, in, def, co };

/* ktm syntax v0.2: `defer <stmt>` runs <stmt> when control leaves the
 * enclosing block: falling off its end, return (after the value is
 * evaluated), break, continue, a goto out of it, or a throw going
 * through it (which then goes on to the next catch). Several run in
 * reverse order, inner blocks first; a defer only runs if it was
 * reached, reads its variables when it runs, and in a loop body runs at
 * the end of every iteration. A coroutine switch is not a way out.
 * Leaving the deferred statement itself (return, break, continue, goto),
 * a goto past a defer into its scope and defer outside a function are
 * compile errors. */

u8 gLog[512];
i64 gLen = 0;

void logFn(u8 *s) {
    while (*s) { gLog[gLen++] = *s++; }
    gLog[gLen] = 0;
}

void logNum(i64 n) {
    u8 buf[24];
    str.formatTo(buf, "%d", n);
    logFn(buf);
}

void reset() {
    gLen = 0;
    gLog[0] = 0;
}

/* The log since the last check is `want`, then start a new one. */
bool got(u8 *want) {
    bool ok = !str.cmp(gLog, want);
    if (!ok) { printf("\n  want \"%s\" got \"%s\"\n", want, gLog); }
    reset();
    return ok;
}

/* LIFO, and the inner block unwinds when it ends */
void orderFn() {
    defer logFn("1");
    {
        defer logFn("2");
        defer { logFn("3"); }
        logFn("a");
    }
    defer logFn("4");
    logFn("b");
}

/* every iteration, also when left by continue and break */
void loop() {
    for (i64 i = 0; i < 5; i++) {
        defer logNum(i);
        if (i == 1) { continue; }
        if (i == 3) { break; }
        logFn(".");
    }
    i64 j = 0;
    while (true) {
        defer logFn("w");
        if (++j == 2) { break; }
    }
}

/* a defer after an early return was not reached and does not run */
i64 early(i64 x) {
    defer logFn("a");
    if (x) { return 1; }
    defer logFn("b");
    return 2;
}

/* the return value is evaluated before the deferred statement runs */
i64 returnValue() {
    i64 *p = mem.alloc(sizeof(i64));
    *p = 42;
    defer { *p = 0; mem.free(p); logFn("f"); }
    return *p;
}

/* variables are read when the deferred statement runs */
void readAtExit() {
    i64 v = 1;
    defer logNum(v);
    v = 7;
}

/* a goto out of the block (and back into the start of it) */
void gotoOut() {
    i64 n = 0;
again:
    {
        defer logNum(n);
        n++;
        if (n < 3) { goto again; }
        goto done;
    }
done:
    logFn("d");
}

/* a throw goes through the defers of two frames and is then caught */
void thrower(i64 *seen) {
    i64 local = 5;
    defer { logFn("t"); *seen = local; }
    local = 9;
    throw(Err.oops);
}

void middle(i64 *seen) {
    defer logFn("m");
    thrower(seen);
    logFn("X");
}

bool unwinds() {
    i64 seen = 0, code = 0;
    try {
        defer logFn("o");
        middle(&seen);
        logFn("Y");
    } catch (Error e) {
        logFn("c");
        code = e.code;
    }
    return got("tmoc") && seen == 9 && code == Err.oops;
}

/* a try inside a block with a defer: caught inside, the block's defer
 * runs at its end as usual */
bool tryInBlock() {
    {
        defer logFn("e");
        try {
            defer logFn("i");
            throw(Err.in);
        } catch {
            logFn("c");
        }
        logFn("n");
    }
    return got("icne");
}

/* a throw from a deferred statement goes on like any throw */
void throwInDefer() {
    defer logFn("2");
    {
        defer throw(Err.def);
        logFn("1");
    }
    logFn("X");
}

bool throwFromDefer() {
    i64 code = 0;
    try { throwInDefer(); } catch (Error e) { logFn("c"); code = e.code; }
    return got("12c") && code == Err.def;
}

/* in a switch case's block, and with loops inside the deferred statement
 * (their break and continue stay inside it) */
i64 inSwitch(i64 x) {
    i64 r = 0;
    switch (x) {
        case 1: {
            defer {
                for (i64 k = 0; k < 9; k++) {
                    if (k == 1) { continue; }
                    if (k == 3) { break; }
                    logNum(k);
                }
            }
            r = 10;
            break;
        }
        default:
            r = 20;
    }
    return r;
}

/* a coroutine switch is not a way out of the block */
void *gen(void *arg) {
    defer logFn("G");
    for (i64 i = 0; i < 2; i++) {
        defer logFn("i");
        coro.yield((void *)i);
        logFn("r");
    }
    return null;
}

bool inCoroutine() {
    coro.Coroutine *c = coro.spawn(&gen, null);
    coro.next(c);
    logFn("|");
    coro.next(c);
    logFn("|");
    coro.next(c);
    bool ok = coro.done(c);
    coro.release(c);
    return ok && got("|ri|riG");
}

/* a throw going through a defer in a coroutine, caught in it */
void *genThrow(void *arg) {
    try {
        defer logFn("d");
        coro.yield(null);
        throw(Err.co);
    } catch {
        logFn("c");
    }
    return null;
}

bool throwInCoroutine() {
    coro.Coroutine *c = coro.spawn(&genThrow, null);
    coro.next(c);
    logFn("|");
    coro.next(c);
    bool ok = coro.done(c);
    coro.release(c);
    return ok && got("|dc");
}

#define DEFER_SRC "./ktm-417-defer.ktm"
#define DEFER_OUT "./ktm-417-defer.out"
#define DEFER_BIN "./ktm-417-defer.bin"

/* Compile `source`; with `run`, run it too. What ktm (and the program)
 * printed. */
u8 *compileRun(u8 *source, bool run) {
    proc.shell("rm -f " DEFER_SRC " " DEFER_OUT " " DEFER_BIN);
    fs.writeAll(DEFER_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    if (run) {
        proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                     "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                     DEFER_SRC " -o " DEFER_BIN " > " DEFER_OUT " 2>&1 && "
                     DEFER_BIN " >> " DEFER_OUT " 2>&1");
    } else {
        proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                     "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                     "-S " DEFER_SRC " -o /dev/null > " DEFER_OUT " 2>&1");
    }
    u8 *out = fs.readAll(DEFER_OUT);
    proc.shell("rm -f " DEFER_SRC " " DEFER_OUT " " DEFER_BIN);
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

/* `body` in a function fails to compile with exactly one error, `want` */
bool fails(u8 *body, u8 *want) {
    u8 *src = str.format(
"i64 Get() { return 1; }
i32 main()
{
  i64 n = 0;
%s
  return 0;
}
", body);
    u8 *out = compileRun(src, false);
    bool ok = out && count(out, "error:") == 1 && libc.strstr(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", body, out); }
    mem.free(out);
    mem.free(src);
    return ok;
}

/* `source` compiles and runs, printing exactly `want` */
bool runs(u8 *source, u8 *want) {
    u8 *out = compileRun(source, true);
    bool ok = out && !str.cmp(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", source, out); }
    mem.free(out);
    return ok;
}

i32 main() {
    printf("Test - syntax: defer: ");
    i64 correct = 0, tests = 0;

    reset();
    tests++; orderFn();          if (got("a32b41")) { correct++; }
    tests++; loop();           if (got(".01.23ww")) { correct++; }
    tests++;
    if (early(1) == 1 && got("a") && early(0) == 2 && got("ba")) { correct++; }
    tests++; if (returnValue() == 42 && got("f")) { correct++; }
    tests++; readAtExit();     if (got("7")) { correct++; }
    tests++; gotoOut();        if (got("123d")) { correct++; }
    tests++; if (unwinds()) { correct++; }
    tests++; if (tryInBlock()) { correct++; }
    tests++; if (throwFromDefer()) { correct++; }
    tests++; if (inSwitch(1) == 10 && got("02") && inSwitch(5) == 20 && got("")) { correct++; }
    tests++; if (inCoroutine()) { correct++; }
    tests++; if (throwInCoroutine()) { correct++; }

    /* returning from main runs its defers, exit() doesn't */
    tests++;
    if (runs("i32 main() { defer printf(\"bye\\n\"); printf(\"hi\\n\"); return 0; }\n",
                     "hi\nbye\n")) {
        correct++;
                     }
    tests++;
    if (runs("import os;\ni32 main() { defer printf(\"bye\\n\"); printf(\"hi\\n\"); os.exit(0); return 1; }\n",
                     "hi\n")) {
        correct++;
                     }

    /* leaving the deferred statement itself */
    tests++;
    if (fails("  defer { return 1; }", "`return` can't leave a deferred statement")) {
        correct++;
    }
    tests++;
    if (fails("  while (n < 3) { n++; defer break; }",
                        "`break` can't leave a deferred statement")) {
        correct++;
                        }
    tests++;
    if (fails("  for (i64 i = 0; i < 3; i++) { defer { if (i) continue; } }",
                        "`continue` can't leave a deferred statement")) {
        correct++;
                        }
    tests++;
    if (fails("  defer { goto out; }\nout:\n  n++;",
                        "`goto` can't leave a deferred statement")) {
        correct++;
                        }
    tests++;
    if (fails("  defer { here: n++; }", "a label can't be inside a deferred statement")) {
        correct++;
    }
    /* a goto past a defer into its scope */
    tests++;
    if (fails("  if (Get()) goto inside;\n  defer n++;\ninside:\n  n++;",
                        "`goto inside` jumps past a `defer` into its scope")) {
        correct++;
                        }
    /* defer outside a function, and outside a { } block */
    tests++;
    if (fails("}\ndefer printf(\"x\");\ni64 F() {", "`defer` is only allowed inside a function")) {
        correct++;
    }
    tests++;
    if (fails("  if (n) defer n++;", "`defer` must be a statement of a { } block")) {
        correct++;
    }
    /* a loop inside a deferred statement keeps its break and continue */
    tests++;
    if (runs(
"i32 main() {
  {
    defer for (i64 i = 0; i < 5; i++) { if (i == 1) continue; if (i == 3) break; printf(\"%d\", i); }
    printf(\"x\");
  }
  printf(\"\\n\");
  return 0;
}
", "x02\n")) {
        correct++;
}

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
