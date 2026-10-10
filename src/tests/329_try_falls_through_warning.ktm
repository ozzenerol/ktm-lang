#include "testhelper.ktm"

/* "control may reach the end of non-void function" was reported for a
 * try whose body and catch handler both end in return: the fall-through
 * analysis did not know AST_TRY. A try falls through iff its body or its
 * handler can. */

#define TRY_FALL_FILE "__329_try_fall.ktm"
#define TRY_FALL_OUT  "__329_try_fall.out"
#define TRY_FALL_MSG  "control may reach the end of non-void function"

I64 BothReturn(I64 x)
{
  try {
    if (x) throw('X');
    return 1;
  } catch {
    return 2;
  }
}

I64 Nested(I64 x)
{
  try {
    try {
      if (x == 1) throw('In');
      if (x == 2) throw('Out');
      return 10;
    } catch {
      if (Fs->except_ch == 'Out') throw('Out');
      return 20;
    }
  } catch {
    return 30;
  }
}

U0 CleanupTryFall(U0)
{
  Rm(TRY_FALL_FILE);
  Rm(TRY_FALL_OUT);
}

/* Compiles and runs `src` with the JIT; TRUE when it succeeds and the
 * warning shows up exactly when `warn` says it should. */
Bool WarnsIf(U8 *src, Bool warn)
{
  U8 *out;
  I64 res;
  Bool ok;

  CleanupTryFall;
  if (!FileWrite(TRY_FALL_FILE, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  res = System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " TRY_FALL_FILE " > " TRY_FALL_OUT " 2>&1");
  out = FileRead(TRY_FALL_OUT);
  ok = res == 0 && out && (strstr(out, TRY_FALL_MSG) != NULL) == warn;
  if (!ok && out) "%s\n", out;
  Free(out);
  CleanupTryFall;
  return ok;
}

I32 Main()
{
  "Test - try falls through warning: ";
  I64 correct = 0, tests = 0;

  tests++; if (BothReturn(0) == 1 && BothReturn(1) == 2) correct++;
  tests++; if (Nested(0) == 10 && Nested(1) == 20 && Nested(2) == 30) correct++;

  /* body and handler return */
  tests++;
  if (WarnsIf(
"I64 F(I64 x)
{
  try {
    if (x) throw('X');
    return 1;
  } catch {
    return 2;
  }
}
U0 Main() { F(1); }
", FALSE)) correct++;

  /* body throws, handler returns */
  tests++;
  if (WarnsIf(
"I64 F()
{
  try {
    throw('X');
  } catch
    return 2;
}
U0 Main() { F; }
", FALSE)) correct++;

  /* nested tries, every path returns or throws */
  tests++;
  if (WarnsIf(
"I64 F(I64 x)
{
  try {
    try {
      return x;
    } catch {
      throw('Again');
    }
  } catch {
    return -1;
  }
}
U0 Main() { F(1); }
", FALSE)) correct++;

  /* handler falls through */
  tests++;
  if (WarnsIf(
"I64 F(I64 x)
{
  try {
    return x;
  } catch {
    x = 0;
  }
}
U0 Main() { F(1); }
", TRUE)) correct++;

  /* body falls through */
  tests++;
  if (WarnsIf(
"I64 F(I64 x)
{
  try {
    x++;
  } catch {
    return 0;
  }
}
U0 Main() { F(1); }
", TRUE)) correct++;

  /* falling through the try, then a return */
  tests++;
  if (WarnsIf(
"I64 F(I64 x)
{
  try {
    x++;
  } catch {
  }
  return x;
}
U0 Main() { F(1); }
", FALSE)) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
