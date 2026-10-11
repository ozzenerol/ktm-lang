import cstr, fs, libc, mem, proc, test;

/* "control may reach the end of non-void function" was reported for a
 * function whose last statement is a goto: the fall-through analysis
 * looked for AST_JUMP, but goto builds AST_GOTO. The warning also pointed
 * at the first token of the body (a label, here) instead of the function.
 * A goto now ends the flow like a return, a label after it makes the code
 * that follows reachable again, and the warning points at the name. */

#define GOTO_FALL_FILE "__367_goto_fall.ktm"
#define GOTO_FALL_OUT  "__367_goto_fall.out"
#define GOTO_FALL_MSG  "control may reach the end of non-void function"

i64 gotoLoop(i64 x) {
top:
    if (x > 3) { return x; }
    x++;
    goto top;
}

i64 gotoExit(i64 x) {
    if (x < 0) { goto bad; }
    return x * 2;
bad:
    return -1;
}

void cleanupGotoFall(void) {
    fs.remove(GOTO_FALL_FILE);
    fs.remove(GOTO_FALL_OUT);
}

/* Compiles and runs `src` with the JIT; true when it succeeds, the
 * warning shows up exactly when `warn` says it should and, if `where` is
 * given, ktm's output has it (the warning's location) */
bool warnsIf(u8 *src, bool warn, u8 *where=null) {
    u8 *out;
    i64 res;
    bool ok;

    cleanupGotoFall();
    if (!fs.writeAll(GOTO_FALL_FILE, cstr.view(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    res = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " GOTO_FALL_FILE " > " GOTO_FALL_OUT " 2>&1");
    out = fs.readAll(GOTO_FALL_OUT).take();
    ok = res == 0 && out && (libc.strstr(out, GOTO_FALL_MSG) != null) == warn;
    if (ok && where) { ok = libc.strstr(out, where) != null; }
    if (!ok && out) { printf("%s\n", out); }
    mem.free(out);
    cleanupGotoFall();
    return ok;
}

i32 main() {
    printf("Test - goto fallthrough warning: ");
    i64 correct = 0, tests = 0;

    tests++; if (gotoLoop(1) == 4 && gotoLoop(9) == 9) { correct++; }
    tests++; if (gotoExit(2) == 4 && gotoExit(-5) == -1) { correct++; }

    /* the repro: a loop made of a label and a trailing goto */
    tests++;
    if (warnsIf(
"i64 F(i64 x){ top: if (x>3) { return x; } x++; goto top; }
void main() { F(1); }
", false)) { correct++; }

    /* `true` is a bool literal: `while (true)` loops for ever like
     * `while (1)`; `while (false)` doesn't */
    tests++;
    if (warnsIf(
"i64 F(i64 x){ while (true) { if (x) { return 1; } } }
i64 G(i64 x){ for (;true;) { if (x) { return 1; } } }
void main() { F(1); G(1); }
", false)) { correct++; }
    tests++;
    if (warnsIf(
"i64 F(i64 x){ while (false) { if (x) { return 1; } } }
void main() { F(1); }
", true)) { correct++; }

    /* goto forward to a label that returns */
    tests++;
    if (warnsIf(
"i64 F(i64 x)
{
  if (x < 0) {
    goto bad;
  }
  return x;
bad:
  return -1;
}
void main() { F(1); }
", false)) { correct++; }

    /* a label after the return: the code after it runs off the end */
    tests++;
    if (warnsIf(
"i64 F(i64 x)
{
  if (x) {
    goto done;
  }
  return 1;
done:
  x++;
}
void main() { F(0); }
", true)) { correct++; }

    /* the label can be inside a nested block */
    tests++;
    if (warnsIf(
"i64 F(i64 x)
{
  while (1) {
    if (x) { goto inner; }
    x++;
    if (x > 3) { return x; }
  }
  if (x > 5) {
inner:
    x--;
  }
}
void main() { F(1); }
", true)) { correct++; }

    /* a goto past a block that holds a label, to a return */
    tests++;
    if (warnsIf(
"i64 F(i64 x)
{
  if (x) { goto inner; }
  goto out;
  if (x > 5) {
inner:
    x--;
  }
out:
  return x;
}
void main() { F(1); }
", false)) { correct++; }

    /* goto out of a loop, then a return */
    tests++;
    if (warnsIf(
"i64 F(i64 x)
{
  while (1) {
    if (x > 10) { goto out; }
    x++;
  }
out:
  return x;
}
void main() { F(0); }
", false)) { correct++; }

    /* the warning points at the function's name, not into its body */
    tests++;
    if (warnsIf(
"void other() {}
i64 fallsOff(i64 x)
{
  x++;
}
void main() { fallsOff(1); }
", true, GOTO_FALL_FILE ":2:5")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
