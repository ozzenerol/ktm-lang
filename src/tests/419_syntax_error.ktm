import coro, cstr, fs, libc, mem, proc, test;

enum Err { old };

/* ktm syntax v0.2: errors in exceptions. A throw carries an Error
 * (code, message, file, line): `throw(code)` (message ""),
 * `throw(code, fmt, ...)` (formatted like printf, format checked at
 * compile time) and `throw(e)` (the same error again, file and line
 * kept). `catch (Error e)` binds it, `catch` without a binding still
 * works. Uncaught it prints
 * `uncaught error <code>: <message> (<file>:<line>)` and exits 1. The
 * error is intact after defers, two call frames, a coroutine and in
 * another thread, and the runtime frees every message. */

enum BankError { funds, locked };

u8 gLog[256];
i64 gLen = 0;

void logFn(u8 *s) {
    while (*s) { gLog[gLen++] = *s++; }
    gLog[gLen] = 0;
}

bool got(u8 *want) {
    bool ok = !cstr.cmp(gLog, want);
    if (!ok) { printf("\n  want \"%s\" got \"%s\"\n", want, gLog); }
    gLen = 0;
    gLog[0] = 0;
    return ok;
}

bool inThisFile(Error *e) {
    return e.file && libc.strstr(e.file, "419_syntax_error.ktm") && e.line > 0;
}

void withdraw(i64 balance, i64 amount) {
    if (amount > balance) { throw(BankError.funds, "need %d, have %d", amount, balance); }
}

/* code only: message is "" (never null) */
bool codeOnly() {
    try {
        throw(BankError.locked);
    } catch (Error e) {
        return e.code == BankError.locked && e.message && e.message[0] == 0 &&
                     inThisFile(&e);
    }
    return false;
}

/* a formatted message, and enum codes */
bool formatted() {
    try {
        withdraw(100, 500);
    } catch (Error e) {
        return e.code == BankError.funds && !cstr.cmp(e.message, "need 500, have 100") &&
                     inThisFile(&e);
    }
    return false;
}

/* throw(e) keeps code, message, file and line */
i64 gInnerLine = 0;

void rethrower() {
    try {
        withdraw(1, 2);
    } catch (Error e) {
        gInnerLine = e.line;
        throw(e);
    }
}

bool rethrow() {
    try {
        rethrower();
    } catch (Error e) {
        return e.code == BankError.funds && !cstr.cmp(e.message, "need 2, have 1") &&
                     inThisFile(&e) && e.line == gInnerLine;
    }
    return false;
}

/* through defers in two call frames */
void inner() {
    defer logFn("i");
    throw(BankError.locked, "locked %s", "account");
}

void outer() {
    defer logFn("o");
    inner();
}

bool throughDefers() {
    try {
        defer logFn("t");
        outer();
    } catch (Error e) {
        logFn("c");
        return got("iotc") && e.code == BankError.locked &&
                     !cstr.cmp(e.message, "locked account") && inThisFile(&e);
    }
    return false;
}

/* a throw caught inside a deferred statement doesn't clobber the error
 * that is passing through */
void deferWithTry() {
    defer {
        try { throw(BankError.funds, "inside the defer"); } catch (Error x) { logFn("x"); }
    }
    throw(BankError.locked, "outer");
}

bool deferKeepsError() {
    try {
        deferWithTry();
    } catch (Error e) {
        return got("x") && e.code == BankError.locked && !cstr.cmp(e.message, "outer");
    }
    return false;
}

/* the old forms next to the new one */
bool oldForms() {
    bool ok = true;
    try { throw(Err.old); } catch (Error e) { ok = ok && e.code == Err.old; }
    try { throw(Err.old); } catch (Error e) { ok = ok && e.code == Err.old && e.message[0] == 0; }
    try { throw(BankError.funds, "msg"); } catch (Error e) { ok = ok && e.code == BankError.funds; }
    return ok;
}

/* an error from a coroutine, rethrown by coro.next */
void *gen(void *arg) {
    defer logFn("g");
    coro.yield((void *)1);
    throw(BankError.locked, "from %s", "coroutine");
    return null;
}

bool inCoroutine() {
    coro.Coroutine *c = coro.spawn(&gen, null);
    bool ok = false;
    coro.next(c);
    try {
        coro.next(c);
    } catch (Error e) {
        ok = e.code == BankError.locked && !cstr.cmp(e.message, "from coroutine") &&
                 inThisFile(&e);
    }
    coro.release(c);
    return ok && got("g");
}

/* each thread has its own current error */
void *worker(void *arg) {
    i64 *ok = arg;
    for (i64 i = 0; i < 100; i++) {
        try {
            throw(BankError.funds, "worker %d", i);
        } catch (Error e) {
            u8 *want = cstr.format("worker %d", i);
            if (cstr.cmp(e.message, want)) { *ok = false; }
            mem.free(want);
        }
    }
    return null;
}

bool perThread() {
    i64 workerOk = true;
    bool ok = false;
    try {
        throw(BankError.locked, "main");
    } catch (Error e) {
        libc.pthread_t *t;
        libc.pthread_create(&t, null, &worker, &workerOk);
        libc.pthread_join(t);
        ok = !cstr.cmp(e.message, "main") && e.code == BankError.locked;
    }
    return ok && workerOk;
}

/* throw/catch with messages in a loop frees every message */
void thrower(i64 i) {
    defer i++;
    throw(BankError.funds, "iteration %d of %s", i, "loop");
}

void middle(i64 i) {
    try { thrower(i); } catch (Error e) { throw(e); }
}

void rounds() {
    for (i64 i = 0; i < 2000; i++) {
        try { middle(i); } catch (Error e) { }
        try { thrower(i); } catch { }
        try {
            throw(BankError.locked, "plain %d", i);
        } catch (Error e) {
            try { throw(BankError.funds, "nested %d", i); } catch (Error f) { }
        }
    }
}

bool noLeak() {
    rounds();
    i64 before = test.heapInUse();
    rounds();
    i64 grew = test.heapInUse() - before;
    if (grew) { printf("\n  heap grew by %d bytes\n", grew); }
    return grew == 0;
}

#define ERR_SRC "./ktm-419-error.ktm"
#define ERR_OUT "./ktm-419-error.out"
#define ERR_BIN "./ktm-419-error.bin"

/* Compile `source` (`flags` added) and, with `run`, run it. What ktm and
 * the program printed; *status is the program's exit status. */
u8 *compileRun(u8 *source, u8 *flags, bool run, i64 *status=null) {
    proc.shell("rm -f " ERR_SRC " " ERR_OUT " " ERR_BIN);
    fs.writeAll(ERR_SRC, source, cstr.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    u8 *cmd;
    if (run) {
        cmd = cstr.format("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                                        "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                                        "%s " ERR_SRC " -o " ERR_BIN " > " ERR_OUT " 2>&1 && "
                                        ERR_BIN " >> " ERR_OUT " 2>&1", flags);
    } else {
        cmd = cstr.format("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                                        "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                                        "%s " ERR_SRC " > " ERR_OUT " 2>&1", flags);
    }
    i64 res = proc.shell(cmd);
    if (status) { *status = res; }
    mem.free(cmd);
    u8 *out = fs.readAll(ERR_OUT);
    proc.shell("rm -f " ERR_SRC " " ERR_OUT " " ERR_BIN);
    return out;
}

bool prints(u8 *source, u8 *flags, bool run, i64 status, u8 *want) {
    i64 res = -1;
    u8 *out = compileRun(source, flags, run, &res);
    bool ok = out && res == status && !cstr.cmp(out, want);
    if (!ok) { printf("\n%s\n---- exit %d\n%s\n", source, res, out); }
    mem.free(out);
    return ok;
}

i64 count(u8 *s, u8 *what) {
    i64 n = 0;
    while (s && (s = libc.strstr(s, what))) {
        n++;
        s++;
    }
    return n;
}

i32 main() {
    printf("Test - syntax: errors in exceptions: ");
    i64 correct = 0, tests = 0;

    tests++; if (codeOnly()) { correct++; }
    tests++; if (formatted()) { correct++; }
    tests++; if (rethrow()) { correct++; }
    tests++; if (throughDefers()) { correct++; }
    tests++; if (deferKeepsError()) { correct++; }
    tests++; if (oldForms()) { correct++; }
    tests++; if (inCoroutine()) { correct++; }
    tests++; if (perThread()) { correct++; }
    tests++; if (noLeak()) { correct++; }

    /* exact file and line, as the source was compiled; rethrow keeps them */
    tests++;
    if (prints(
"enum E { funds };
void W(i64 have, i64 want) {
  if (want > have) { throw(E.funds, \"need %d, have %d\", want, have); }
}
i32 main() {
  try { W(100, 500); }
  catch (Error e) {
    try { throw(e); }
    catch (Error again) printf(\"%d|%s|%s|%d\\n\", again.code, again.message, again.file, again.line);
  }
  return 0;
}
", "", true, 0, "0|need 500, have 100|" ERR_SRC "|3\n")) {
        correct++;
}

    /* uncaught: on stderr, exit status 1 */
    tests++;
    if (prints(
"enum E { funds, locked };
i32 main() {
  printf(\"before\\n\");
  throw(E.locked, \"account %d is locked\", 42);
  return 0;
}
", "", true, 1, "before\nuncaught error 1: account 42 is locked (" ERR_SRC ":4)\n")) {
        correct++;
}

    /* the loop under -Memsafe reports nothing */
    tests++;
    if (prints(
"i32 main() {
  for (i64 i = 0; i < 200; i++) {
    try { throw(1, \"m %d\", i); } catch (Error e) { }
    try { throw(2, \"n %d\", i); } catch { }
  }
  printf(\"ok\\n\");
  return 0;
}
", "-jit -Memsafe", false, 0, "ok\n")) {
        correct++;
}

    /* the format and its arguments are checked like printf's */
    tests++;
    u8 *out = compileRun("i32 main() { throw(1, \"%d and %d\", 5); return 0; }\n",
                                             "-S -o /dev/null", false);
    if (out && count(out, "error:") == 1 &&
            libc.strstr(out, "format expects 2 arguments but 1 was supplied")) {
        correct++;
            } else { printf("\n%s\n", out); }
    mem.free(out);

    /* catch can only bind an Error, and only an Error rethrows as one */
    tests++;
    out = compileRun("i32 main() { try { } catch (i64 x) { } return 0; }\n",
                                     "-S -o /dev/null", false);
    if (out && libc.strstr(out, "`catch` can only bind an Error")) { correct++; }
    else { printf("\n%s\n", out); }
    mem.free(out);
    tests++;
    out = compileRun("struct P { i64 a; };\ni32 main() { P p; throw(p); return 0; }\n",
                                     "-S -o /dev/null", false);
    if (out && libc.strstr(out, "`throw` needs an integer code (usually an enum member) or an Error")) {
        correct++;
    } else { printf("\n%s\n", out); }
    mem.free(out);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
