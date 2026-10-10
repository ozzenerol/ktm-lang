#include "testhelper.ktm"

/* ktm syntax v0.2: errors in exceptions. A throw carries an Error
 * (code, message, file, line): `throw(code)` (message ""),
 * `throw(code, fmt, ...)` (formatted like printf, format checked at
 * compile time) and `throw(e)` (the same error again, file and line
 * kept). `catch (Error e)` binds it, `catch` without a binding still
 * works and Fs->except_ch still holds the code. Uncaught it prints
 * `uncaught error <code>: <message> (<file>:<line>)` and exits 1. The
 * error is intact after defers, two call frames, a coroutine and in
 * another thread, and the runtime frees every message. */

enum BankError { funds, locked };

U8 g_log[256];
I64 g_len = 0;

U0 Log(U8 *s)
{
  while (*s) g_log[g_len++] = *s++;
  g_log[g_len] = 0;
}

Bool Got(U8 *want)
{
  Bool ok = !StrCmp(g_log, want);
  if (!ok) "\n  want \"%s\" got \"%s\"\n", want, g_log;
  g_len = 0;
  g_log[0] = 0;
  return ok;
}

Bool InThisFile(Error *e)
{
  return e->file && strstr(e->file, "419_syntax_error.ktm") && e->line > 0;
}

U0 Withdraw(I64 balance, I64 amount)
{
  if (amount > balance) throw(BankError.funds, "need %d, have %d", amount, balance);
}

/* code only: message is "" (never NULL) */
Bool CodeOnly()
{
  try {
    throw(BankError.locked);
  } catch (Error e) {
    return e.code == BankError.locked && e.message && e.message[0] == 0 &&
           InThisFile(&e) && Fs->except_ch == BankError.locked;
  }
  return FALSE;
}

/* a formatted message, and enum codes */
Bool Formatted()
{
  try {
    Withdraw(100, 500);
  } catch (Error e) {
    return e.code == BankError.funds && !StrCmp(e.message, "need 500, have 100") &&
           InThisFile(&e);
  }
  return FALSE;
}

/* throw(e) keeps code, message, file and line */
I64 g_inner_line = 0;

U0 Rethrower()
{
  try {
    Withdraw(1, 2);
  } catch (Error e) {
    g_inner_line = e.line;
    throw(e);
  }
}

Bool Rethrow()
{
  try {
    Rethrower();
  } catch (Error e) {
    return e.code == BankError.funds && !StrCmp(e.message, "need 2, have 1") &&
           InThisFile(&e) && e.line == g_inner_line;
  }
  return FALSE;
}

/* through defers in two call frames */
U0 Inner()
{
  defer Log("i");
  throw(BankError.locked, "locked %s", "account");
}

U0 Outer()
{
  defer Log("o");
  Inner();
}

Bool ThroughDefers()
{
  try {
    defer Log("t");
    Outer();
  } catch (Error e) {
    Log("c");
    return Got("iotc") && e.code == BankError.locked &&
           !StrCmp(e.message, "locked account") && InThisFile(&e);
  }
  return FALSE;
}

/* a throw caught inside a deferred statement doesn't clobber the error
 * that is passing through */
U0 DeferWithTry()
{
  defer {
    try { throw(BankError.funds, "inside the defer"); } catch (Error x) { Log("x"); }
  }
  throw(BankError.locked, "outer");
}

Bool DeferKeepsError()
{
  try {
    DeferWithTry();
  } catch (Error e) {
    return Got("x") && e.code == BankError.locked && !StrCmp(e.message, "outer");
  }
  return FALSE;
}

/* the old forms next to the new one */
Bool OldForms()
{
  Bool ok = TRUE;
  try { throw('Old'); } catch { ok = ok && Fs->except_ch == 'Old'; }
  try { throw('Old'); } catch (Error e) { ok = ok && e.code == 'Old' && e.message[0] == 0; }
  try { throw(BankError.funds, "msg"); } catch { ok = ok && Fs->except_ch == BankError.funds; }
  return ok;
}

/* an error from a coroutine, rethrown by CoroutineNext */
U0 *Gen(U0 *arg)
{
  defer Log("g");
  CoroutineYield(1(U0 *));
  throw(BankError.locked, "from %s", "coroutine");
  return NULL;
}

Bool InCoroutine()
{
  Coroutine *c = CoroutineNew(&Gen, NULL);
  Bool ok = FALSE;
  CoroutineNext(c);
  try {
    CoroutineNext(c);
  } catch (Error e) {
    ok = e.code == BankError.locked && !StrCmp(e.message, "from coroutine") &&
         InThisFile(&e);
  }
  CoroutineRelease(c);
  return ok && Got("g");
}

/* each thread has its own current error */
U0 *Worker(U0 *arg)
{
  I64 *ok = arg;
  for (I64 i = 0; i < 100; i++) {
    try {
      throw(BankError.funds, "worker %d", i);
    } catch (Error e) {
      U8 *want = MStrPrint("worker %d", i);
      if (StrCmp(e.message, want)) *ok = FALSE;
      Free(want);
    }
  }
  return NULL;
}

Bool PerThread()
{
  I64 worker_ok = TRUE;
  Bool ok = FALSE;
  try {
    throw(BankError.locked, "main");
  } catch (Error e) {
    pthread_t *t;
    pthread_create(&t, NULL, &Worker, &worker_ok);
    pthread_join(t);
    ok = !StrCmp(e.message, "main") && e.code == BankError.locked;
  }
  return ok && worker_ok;
}

/* throw/catch with messages in a loop frees every message */
U0 Thrower(I64 i)
{
  defer i++;
  throw(BankError.funds, "iteration %d of %s", i, "loop");
}

U0 Middle(I64 i)
{
  try { Thrower(i); } catch (Error e) { throw(e); }
}

U0 Rounds()
{
  for (I64 i = 0; i < 2000; i++) {
    try { Middle(i); } catch (Error e) { }
    try { Thrower(i); } catch { }
    try {
      throw(BankError.locked, "plain %d", i);
    } catch (Error e) {
      try { throw(BankError.funds, "nested %d", i); } catch (Error f) { }
    }
  }
}

Bool NoLeak()
{
  Rounds;
  I64 before = HeapInUse();
  Rounds;
  I64 grew = HeapInUse() - before;
  if (grew) "\n  heap grew by %d bytes\n", grew;
  return grew == 0;
}

#define ERR_SRC "./ktm-419-error.ktm"
#define ERR_OUT "./ktm-419-error.out"
#define ERR_BIN "./ktm-419-error.bin"

/* Compile `source` (`flags` added) and, with `run`, run it. What ktm and
 * the program printed; *status is the program's exit status. */
U8 *CompileRun(U8 *source, U8 *flags, Bool run, I64 *status=NULL)
{
  System("rm -f " ERR_SRC " " ERR_OUT " " ERR_BIN);
  FileWrite(ERR_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  U8 *cmd;
  if (run) {
    cmd = MStrPrint("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                    "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                    "%s " ERR_SRC " -o " ERR_BIN " > " ERR_OUT " 2>&1 && "
                    ERR_BIN " >> " ERR_OUT " 2>&1", flags);
  } else {
    cmd = MStrPrint("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                    "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                    "%s " ERR_SRC " > " ERR_OUT " 2>&1", flags);
  }
  I64 res = System(cmd);
  if (status) *status = res;
  Free(cmd);
  U8 *out = FileRead(ERR_OUT);
  System("rm -f " ERR_SRC " " ERR_OUT " " ERR_BIN);
  return out;
}

Bool Prints(U8 *source, U8 *flags, Bool run, I64 status, U8 *want)
{
  I64 res = -1;
  U8 *out = CompileRun(source, flags, run, &res);
  Bool ok = out && res == status && !StrCmp(out, want);
  if (!ok) "\n%s\n---- exit %d\n%s\n", source, res, out;
  Free(out);
  return ok;
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

I32 Main()
{
  "Test - syntax: errors in exceptions: ";
  I64 correct = 0, tests = 0;

  tests++; if (CodeOnly()) correct++;
  tests++; if (Formatted()) correct++;
  tests++; if (Rethrow()) correct++;
  tests++; if (ThroughDefers()) correct++;
  tests++; if (DeferKeepsError()) correct++;
  tests++; if (OldForms()) correct++;
  tests++; if (InCoroutine()) correct++;
  tests++; if (PerThread()) correct++;
  tests++; if (NoLeak()) correct++;

  /* exact file and line, as the source was compiled; rethrow keeps them */
  tests++;
  if (Prints(
"enum E { funds };
void W(i64 have, i64 want) {
  if (want > have) throw(E.funds, \"need %d, have %d\", want, have);
}
i32 main() {
  try { W(100, 500); }
  catch (Error e) {
    try { throw(e); }
    catch (Error again) printf(\"%d|%s|%s|%d\\n\", again.code, again.message, again.file, again.line);
  }
  return 0;
}
", "", TRUE, 0, "0|need 500, have 100|" ERR_SRC "|3\n"))
    correct++;

  /* uncaught: on stderr, exit status 1 */
  tests++;
  if (Prints(
"enum E { funds, locked };
i32 main() {
  printf(\"before\\n\");
  throw(E.locked, \"account %d is locked\", 42);
  return 0;
}
", "", TRUE, 1, "before\nuncaught error 1: account 42 is locked (" ERR_SRC ":4)\n"))
    correct++;

  /* the loop under -Memsafe reports nothing */
  tests++;
  if (Prints(
"i32 main() {
  for (i64 i = 0; i < 200; i++) {
    try { throw(1, \"m %d\", i); } catch (Error e) { }
    try { throw(2, \"n %d\", i); } catch { }
  }
  printf(\"ok\\n\");
  return 0;
}
", "-jit -Memsafe", FALSE, 0, "ok\n"))
    correct++;

  /* the format and its arguments are checked like printf's */
  tests++;
  U8 *out = CompileRun("i32 main() { throw(1, \"%d and %d\", 5); return 0; }\n",
                       "-S -o /dev/null", FALSE);
  if (out && Count(out, "error:") == 1 &&
      strstr(out, "format expects 2 arguments but 1 was supplied"))
    correct++;
  else "\n%s\n", out;
  Free(out);

  /* catch can only bind an Error, and only an Error rethrows as one */
  tests++;
  out = CompileRun("i32 main() { try { } catch (i64 x) { } return 0; }\n",
                   "-S -o /dev/null", FALSE);
  if (out && strstr(out, "`catch` can only bind an Error")) correct++;
  else "\n%s\n", out;
  Free(out);
  tests++;
  out = CompileRun("struct P { i64 a; };\ni32 main() { P p; throw(p); return 0; }\n",
                   "-S -o /dev/null", FALSE);
  if (out && strstr(out, "`throw` needs an integer code (usually an enum member) or an Error"))
    correct++;
  else "\n%s\n", out;
  Free(out);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
