#include "testhelper.ktm"

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

U8 g_log[512];
I64 g_len = 0;

U0 Log(U8 *s)
{
  while (*s) g_log[g_len++] = *s++;
  g_log[g_len] = 0;
}

U0 LogNum(I64 n)
{
  U8 buf[24];
  StrPrint(buf, "%d", n);
  Log(buf);
}

U0 Reset()
{
  g_len = 0;
  g_log[0] = 0;
}

/* The log since the last check is `want`, then start a new one. */
Bool Got(U8 *want)
{
  Bool ok = !StrCmp(g_log, want);
  if (!ok) "\n  want \"%s\" got \"%s\"\n", want, g_log;
  Reset;
  return ok;
}

/* LIFO, and the inner block unwinds when it ends */
U0 Order()
{
  defer Log("1");
  {
    defer Log("2");
    defer { Log("3"); }
    Log("a");
  }
  defer Log("4");
  Log("b");
}

/* every iteration, also when left by continue and break */
U0 Loop()
{
  for (I64 i = 0; i < 5; i++) {
    defer LogNum(i);
    if (i == 1) continue;
    if (i == 3) break;
    Log(".");
  }
  I64 j = 0;
  while (TRUE) {
    defer Log("w");
    if (++j == 2) break;
  }
}

/* a defer after an early return was not reached and does not run */
I64 Early(I64 x)
{
  defer Log("a");
  if (x) return 1;
  defer Log("b");
  return 2;
}

/* the return value is evaluated before the deferred statement runs */
I64 ReturnValue()
{
  I64 *p = MAlloc(sizeof(I64));
  *p = 42;
  defer { *p = 0; Free(p); Log("f"); }
  return *p;
}

/* variables are read when the deferred statement runs */
U0 ReadAtExit()
{
  I64 v = 1;
  defer LogNum(v);
  v = 7;
}

/* a goto out of the block (and back into the start of it) */
U0 GotoOut()
{
  I64 n = 0;
again:
  {
    defer LogNum(n);
    n++;
    if (n < 3) goto again;
    goto done;
  }
done:
  Log("d");
}

/* a throw goes through the defers of two frames and is then caught */
U0 Thrower(I64 *seen)
{
  I64 local = 5;
  defer { Log("t"); *seen = local; }
  local = 9;
  throw('Oops');
}

U0 Middle(I64 *seen)
{
  defer Log("m");
  Thrower(seen);
  Log("X");
}

Bool Unwinds()
{
  I64 seen = 0;
  try {
    defer Log("o");
    Middle(&seen);
    Log("Y");
  } catch {
    Log("c");
  }
  return Got("tmoc") && seen == 9 && Fs->except_ch == 'Oops';
}

/* a try inside a block with a defer: caught inside, the block's defer
 * runs at its end as usual */
Bool TryInBlock()
{
  {
    defer Log("e");
    try {
      defer Log("i");
      throw('In');
    } catch {
      Log("c");
    }
    Log("n");
  }
  return Got("icne");
}

/* a throw from a deferred statement goes on like any throw */
U0 ThrowInDefer()
{
  defer Log("2");
  {
    defer throw('Def');
    Log("1");
  }
  Log("X");
}

Bool ThrowFromDefer()
{
  try { ThrowInDefer(); } catch { Log("c"); }
  return Got("12c") && Fs->except_ch == 'Def';
}

/* in a switch case's block, and with loops inside the deferred statement
 * (their break and continue stay inside it) */
I64 InSwitch(I64 x)
{
  I64 r = 0;
  switch (x) {
    case 1: {
      defer {
        for (I64 k = 0; k < 9; k++) {
          if (k == 1) continue;
          if (k == 3) break;
          LogNum(k);
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
U0 *Gen(U0 *arg)
{
  defer Log("G");
  for (I64 i = 0; i < 2; i++) {
    defer Log("i");
    CoroutineYield(i(U0 *));
    Log("r");
  }
  return NULL;
}

Bool InCoroutine()
{
  Coroutine *c = CoroutineNew(&Gen, NULL);
  CoroutineNext(c);
  Log("|");
  CoroutineNext(c);
  Log("|");
  CoroutineNext(c);
  Bool ok = CoroutineDone(c);
  CoroutineRelease(c);
  return ok && Got("|ri|riG");
}

/* a throw going through a defer in a coroutine, caught in it */
U0 *GenThrow(U0 *arg)
{
  try {
    defer Log("d");
    CoroutineYield(NULL);
    throw('Co');
  } catch {
    Log("c");
  }
  return NULL;
}

Bool ThrowInCoroutine()
{
  Coroutine *c = CoroutineNew(&GenThrow, NULL);
  CoroutineNext(c);
  Log("|");
  CoroutineNext(c);
  Bool ok = CoroutineDone(c);
  CoroutineRelease(c);
  return ok && Got("|dc");
}

#define DEFER_SRC "./ktm-417-defer.ktm"
#define DEFER_OUT "./ktm-417-defer.out"
#define DEFER_BIN "./ktm-417-defer.bin"

/* Compile `source`; with `run`, run it too. What ktm (and the program)
 * printed. */
U8 *CompileRun(U8 *source, Bool run)
{
  System("rm -f " DEFER_SRC " " DEFER_OUT " " DEFER_BIN);
  FileWrite(DEFER_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  if (run) {
    System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
           "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
           DEFER_SRC " -o " DEFER_BIN " > " DEFER_OUT " 2>&1 && "
           DEFER_BIN " >> " DEFER_OUT " 2>&1");
  } else {
    System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
           "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
           "-S " DEFER_SRC " -o /dev/null > " DEFER_OUT " 2>&1");
  }
  U8 *out = FileRead(DEFER_OUT);
  System("rm -f " DEFER_SRC " " DEFER_OUT " " DEFER_BIN);
  return out;
}

I64 Count(U8 *s, U8 *what)
{
  I64 n = 0;
  while (s && (s = strstr(s, what))) {
    n++;
    s++;
  }
  return n;
}

/* `body` in a function fails to compile with exactly one error, `want` */
Bool Fails(U8 *body, U8 *want)
{
  U8 *src = MStrPrint(
"i64 Get() { return 1; }
i32 main()
{
  i64 n = 0;
%s
  return 0;
}
", body);
  U8 *out = CompileRun(src, FALSE);
  Bool ok = out && Count(out, "error:") == 1 && strstr(out, want);
  if (!ok) "\n%s\n----\n%s\n", body, out;
  Free(out);
  Free(src);
  return ok;
}

/* `source` compiles and runs, printing exactly `want` */
Bool Runs(U8 *source, U8 *want)
{
  U8 *out = CompileRun(source, TRUE);
  Bool ok = out && !StrCmp(out, want);
  if (!ok) "\n%s\n----\n%s\n", source, out;
  Free(out);
  return ok;
}

I32 Main()
{
  "Test - syntax: defer: ";
  I64 correct = 0, tests = 0;

  Reset;
  tests++; Order;          if (Got("a32b41")) correct++;
  tests++; Loop;           if (Got(".01.23ww")) correct++;
  tests++;
  if (Early(1) == 1 && Got("a") && Early(0) == 2 && Got("ba")) correct++;
  tests++; if (ReturnValue() == 42 && Got("f")) correct++;
  tests++; ReadAtExit;     if (Got("7")) correct++;
  tests++; GotoOut;        if (Got("123d")) correct++;
  tests++; if (Unwinds()) correct++;
  tests++; if (TryInBlock()) correct++;
  tests++; if (ThrowFromDefer()) correct++;
  tests++; if (InSwitch(1) == 10 && Got("02") && InSwitch(5) == 20 && Got("")) correct++;
  tests++; if (InCoroutine()) correct++;
  tests++; if (ThrowInCoroutine()) correct++;

  /* returning from main runs its defers, exit() doesn't */
  tests++;
  if (Runs("i32 main() { defer printf(\"bye\\n\"); printf(\"hi\\n\"); return 0; }\n",
           "hi\nbye\n"))
    correct++;
  tests++;
  if (Runs("i32 main() { defer printf(\"bye\\n\"); printf(\"hi\\n\"); Exit(0); return 1; }\n",
           "hi\n"))
    correct++;

  /* leaving the deferred statement itself */
  tests++;
  if (Fails("  defer { return 1; }", "`return` can't leave a deferred statement"))
    correct++;
  tests++;
  if (Fails("  while (n < 3) { n++; defer break; }",
            "`break` can't leave a deferred statement"))
    correct++;
  tests++;
  if (Fails("  for (i64 i = 0; i < 3; i++) { defer { if (i) continue; } }",
            "`continue` can't leave a deferred statement"))
    correct++;
  tests++;
  if (Fails("  defer { goto out; }\nout:\n  n++;",
            "`goto` can't leave a deferred statement"))
    correct++;
  tests++;
  if (Fails("  defer { here: n++; }", "a label can't be inside a deferred statement"))
    correct++;
  /* a goto past a defer into its scope */
  tests++;
  if (Fails("  if (Get()) goto inside;\n  defer n++;\ninside:\n  n++;",
            "`goto inside` jumps past a `defer` into its scope"))
    correct++;
  /* defer outside a function, and outside a { } block */
  tests++;
  if (Fails("}\ndefer printf(\"x\");\ni64 F() {", "`defer` is only allowed inside a function"))
    correct++;
  tests++;
  if (Fails("  if (n) defer n++;", "`defer` must be a statement of a { } block"))
    correct++;
  /* a loop inside a deferred statement keeps its break and continue */
  tests++;
  if (Runs(
"i32 main() {
  {
    defer for (i64 i = 0; i < 5; i++) { if (i == 1) continue; if (i == 3) break; printf(\"%d\", i); }
    printf(\"x\");
  }
  printf(\"\\n\");
  return 0;
}
", "x02\n"))
    correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
