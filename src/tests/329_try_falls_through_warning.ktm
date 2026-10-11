import cstr, fs, libc, mem, proc, test;

enum Err { x, in, out };

/* "control may reach the end of non-void function" was reported for a
 * try whose body and catch handler both end in return: the fall-through
 * analysis did not know AST_TRY. A try falls through iff its body or its
 * handler can. */

#define TRY_FALL_FILE "__329_try_fall.ktm"
#define TRY_FALL_OUT  "__329_try_fall.out"
#define TRY_FALL_MSG  "control may reach the end of non-void function"

i64 bothReturn(i64 x) {
    try {
        if (x) { throw(Err.x); }
        return 1;
    } catch {
        return 2;
    }
}

i64 nested(i64 x) {
    try {
        try {
            if (x == 1) { throw(Err.in); }
            if (x == 2) { throw(Err.out); }
            return 10;
        } catch (Error e) {
            if (e.code == Err.out) { throw(Err.out); }
            return 20;
        }
    } catch {
        return 30;
    }
}

void cleanupTryFall(void) {
    fs.remove(TRY_FALL_FILE);
    fs.remove(TRY_FALL_OUT);
}

/* Compiles and runs `src` with the JIT; true when it succeeds and the
 * warning shows up exactly when `warn` says it should. */
bool warnsIf(u8 *src, bool warn) {
    u8 *out;
    i64 res;
    bool ok;

    cleanupTryFall();
    if (!fs.writeAll(TRY_FALL_FILE, cstr.view(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    res = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " TRY_FALL_FILE " > " TRY_FALL_OUT " 2>&1");
    out = fs.readAll(TRY_FALL_OUT).take();
    ok = res == 0 && out && (libc.strstr(out, TRY_FALL_MSG) != null) == warn;
    if (!ok && out) { printf("%s\n", out); }
    mem.free(out);
    cleanupTryFall();
    return ok;
}

i32 main() {
    printf("Test - try falls through warning: ");
    i64 correct = 0, tests = 0;

    tests++; if (bothReturn(0) == 1 && bothReturn(1) == 2) { correct++; }
    tests++; if (nested(0) == 10 && nested(1) == 20 && nested(2) == 30) { correct++; }

    /* body and handler return */
    tests++;
    if (warnsIf(
"enum Err { x };

i64 F(i64 x)
{
  try {
    if (x) { throw(Err.x); }
    return 1;
  } catch {
    return 2;
  }
}
void main() { F(1); }
", false)) { correct++; }

    /* body throws, handler returns */
    tests++;
    if (warnsIf(
"enum Err { x };

i64 F()
{
  try {
    throw(Err.x);
  } catch {
    return 2;
  }
}
void main() { F(); }
", false)) { correct++; }

    /* nested tries, every path returns or throws */
    tests++;
    if (warnsIf(
"enum Err { again };

i64 F(i64 x)
{
  try {
    try {
      return x;
    } catch {
      throw(Err.again);
    }
  } catch {
    return -1;
  }
}
void main() { F(1); }
", false)) { correct++; }

    /* handler falls through */
    tests++;
    if (warnsIf(
"i64 F(i64 x)
{
  try {
    return x;
  } catch {
    x = 0;
  }
}
void main() { F(1); }
", true)) { correct++; }

    /* body falls through */
    tests++;
    if (warnsIf(
"i64 F(i64 x)
{
  try {
    x++;
  } catch {
    return 0;
  }
}
void main() { F(1); }
", true)) { correct++; }

    /* falling through the try, then a return */
    tests++;
    if (warnsIf(
"i64 F(i64 x)
{
  try {
    x++;
  } catch {
  }
  return x;
}
void main() { F(1); }
", false)) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
