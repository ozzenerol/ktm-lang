#include "testhelper.ktm"

/* A goto into a try body from outside it (or from its catch handler)
 * skipped KTM_PushFrame, and the body's normal exit then popped a frame
 * that was never pushed. It is a compile error now; jumps inside a try
 * body and out of it stay legal. */

#define GOTO_TRY_FILE "__328_goto_into_try.ktm"
#define GOTO_TRY_OUT  "__328_goto_into_try.out"

I64 CatchDepth()
{
  if (!Fs) return 0;
  I64 n = 0;
  CatchFrame *f = Fs->catch_top;
  while (f) {
    n++;
    f = f->prev;
  }
  return n;
}

I64 WithinBody(I64 x)
{
  I64 n = 0;
  try {
    if (x) goto skip;
    n += 1;
skip:
    n += 10;
    if (n < 30) goto skip;
  } catch {
    n = -1;
  }
  return n;
}

I64 OutOfBody(I64 x)
{
  I64 n = 0;
  try {
    if (x) goto out;
    n = 1;
  } catch {
    n = -1;
  }
  return n;
out:
  return 100 + CatchDepth;
}

I64 InnerToOuter()
{
  I64 n = 0;
  try {
    try {
      goto outer_label;
    } catch {
    }
    n = -1;
outer_label:
    n += CatchDepth;
  } catch {
    n = -2;
  }
  return n;
}

I64 IntoCatchHandler(I64 x)
{
  I64 n = 0;
  if (x) goto handler;
  try {
    n = 1;
  } catch {
handler:
    n += 50;
  }
  return n + CatchDepth;
}

I64 ThrowAfterJumps()
{
  I64 n = 0;
  try {
    WithinBody(1);
    OutOfBody(1);
    InnerToOuter;
    throw('Done');
  } catch {
    n = 1;
  }
  return n;
}

U0 CleanupGotoTry(U0)
{
  Rm(GOTO_TRY_FILE);
  Rm(GOTO_TRY_OUT);
  Rm("./a.out");
}

Bool CompileFailsWith(U8 *src, U8 *expected)
{
  U8 *out;
  I64 res;
  Bool ok;

  CleanupGotoTry;
  if (!FileWrite(GOTO_TRY_FILE, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  res = System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " GOTO_TRY_FILE " > " GOTO_TRY_OUT " 2>&1");
  out = FileRead(GOTO_TRY_OUT);
  ok = res != 0 && out && strstr(out, expected) != NULL;
  if (!ok && out) "%s\n", out;
  Free(out);
  CleanupGotoTry;
  return ok;
}

I32 Main()
{
  "Test - goto into try: ";
  I64 correct = 0, tests = 0;

  tests++; if (WithinBody(0) == 31 && WithinBody(1) == 30) correct++;
  tests++; if (OutOfBody(0) == 1 && OutOfBody(1) == 100) correct++;
  tests++; if (InnerToOuter == 1) correct++;
  tests++; if (IntoCatchHandler(0) == 1 && IntoCatchHandler(1) == 50) correct++;
  tests++; if (ThrowAfterJumps == 1 && CatchDepth == 0) correct++;

  tests++;
  if (CompileFailsWith(
"U0 Main()
{
  I64 x = 1;
  if (x) goto inside;
  try {
inside:
    \"in\\n\";
  } catch {
  }
}
", "`goto inside` jumps into a try body")) correct++;

  tests++;
  if (CompileFailsWith(
"U0 Main()
{
  try {
    throw('A');
again:
    \"again\\n\";
  } catch {
    goto again;
  }
}
", ":8:")) correct++;

  tests++;
  if (CompileFailsWith(
"U0 Main()
{
  I64 i = 0;
  try {
    \"x\\n\";
back:
    i++;
  } catch {
  }
  if (i < 3) goto back;
}
", "`goto back` jumps into a try body")) correct++;

  tests++;
  if (CompileFailsWith(
"U0 Main()
{
  try {
    goto deeper;
    try {
      while (1) {
        switch (1) {
          case 1:
deeper:
            break;
        }
        break;
      }
    } catch {
    }
  } catch {
  }
}
", "`goto deeper` jumps into a try body")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
