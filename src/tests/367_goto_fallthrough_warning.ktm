#include "testhelper.ktm"

/* "control may reach the end of non-void function" was reported for a
 * function whose last statement is a goto: the fall-through analysis
 * looked for AST_JUMP, but goto builds AST_GOTO. The warning also pointed
 * at the first token of the body (a label, here) instead of the function.
 * A goto now ends the flow like a return, a label after it makes the code
 * that follows reachable again, and the warning points at the name. */

#define GOTO_FALL_FILE "__367_goto_fall.ktm"
#define GOTO_FALL_OUT  "__367_goto_fall.out"
#define GOTO_FALL_MSG  "control may reach the end of non-void function"

I64 GotoLoop(I64 x)
{
top:
  if (x > 3) return x;
  x++;
  goto top;
}

I64 GotoExit(I64 x)
{
  if (x < 0) goto bad;
  return x * 2;
bad:
  return -1;
}

U0 CleanupGotoFall(U0)
{
  Rm(GOTO_FALL_FILE);
  Rm(GOTO_FALL_OUT);
}

/* Compiles and runs `src` with the JIT; TRUE when it succeeds, the
 * warning shows up exactly when `warn` says it should and, if `where` is
 * given, ktm's output has it (the warning's location) */
Bool WarnsIf(U8 *src, Bool warn, U8 *where=NULL)
{
  U8 *out;
  I64 res;
  Bool ok;

  CleanupGotoFall;
  if (!FileWrite(GOTO_FALL_FILE, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  res = System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " GOTO_FALL_FILE " > " GOTO_FALL_OUT " 2>&1");
  out = FileRead(GOTO_FALL_OUT);
  ok = res == 0 && out && (strstr(out, GOTO_FALL_MSG) != NULL) == warn;
  if (ok && where) ok = strstr(out, where) != NULL;
  if (!ok && out) "%s\n", out;
  Free(out);
  CleanupGotoFall;
  return ok;
}

I32 Main()
{
  "Test - goto fallthrough warning: ";
  I64 correct = 0, tests = 0;

  tests++; if (GotoLoop(1) == 4 && GotoLoop(9) == 9) correct++;
  tests++; if (GotoExit(2) == 4 && GotoExit(-5) == -1) correct++;

  /* the repro: a loop made of a label and a trailing goto */
  tests++;
  if (WarnsIf(
"I64 F(I64 x){ top: if (x>3) return x; x++; goto top; }
U0 Main() { F(1); }
", FALSE)) correct++;

  /* `true` is a bool literal: `while (true)` loops for ever like
   * `while (1)`; `while (false)` doesn't */
  tests++;
  if (WarnsIf(
"I64 F(I64 x){ while (true) { if (x) return 1; } }
I64 G(I64 x){ for (;true;) { if (x) return 1; } }
U0 Main() { F(1); G(1); }
", FALSE)) correct++;
  tests++;
  if (WarnsIf(
"I64 F(I64 x){ while (false) { if (x) return 1; } }
U0 Main() { F(1); }
", TRUE)) correct++;

  /* goto forward to a label that returns */
  tests++;
  if (WarnsIf(
"I64 F(I64 x)
{
  if (x < 0)
    goto bad;
  return x;
bad:
  return -1;
}
U0 Main() { F(1); }
", FALSE)) correct++;

  /* a label after the return: the code after it runs off the end */
  tests++;
  if (WarnsIf(
"I64 F(I64 x)
{
  if (x)
    goto done;
  return 1;
done:
  x++;
}
U0 Main() { F(0); }
", TRUE)) correct++;

  /* the label can be inside a nested block */
  tests++;
  if (WarnsIf(
"I64 F(I64 x)
{
  while (1) {
    if (x) goto inner;
    x++;
    if (x > 3) return x;
  }
  if (x > 5) {
inner:
    x--;
  }
}
U0 Main() { F(1); }
", TRUE)) correct++;

  /* a goto past a block that holds a label, to a return */
  tests++;
  if (WarnsIf(
"I64 F(I64 x)
{
  if (x) goto inner;
  goto out;
  if (x > 5) {
inner:
    x--;
  }
out:
  return x;
}
U0 Main() { F(1); }
", FALSE)) correct++;

  /* goto out of a loop, then a return */
  tests++;
  if (WarnsIf(
"I64 F(I64 x)
{
  while (1) {
    if (x > 10) goto out;
    x++;
  }
out:
  return x;
}
U0 Main() { F(0); }
", FALSE)) correct++;

  /* the warning points at the function's name, not into its body */
  tests++;
  if (WarnsIf(
"U0 Other() {}
I64 FallsOff(I64 x)
{
  x++;
}
U0 Main() { FallsOff(1); }
", TRUE, GOTO_FALL_FILE ":2:5")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
