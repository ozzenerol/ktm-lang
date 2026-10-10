#include "testhelper.ktm"

/* A program may define its own version of a standard-library function
 * (here `Now()`, `public CDate Now();` in std.ktmh). That worked in the
 * JIT, but an AOT build failed to link with "multiple definition of
 * `Now'" as soon as anything else from libktm was used: libktm is one
 * object. Library functions are now weak definitions, so the program's
 * own one wins, as in the JIT. A definition or prototype whose
 * signature differs from an earlier one (the library's included) is an
 * error at its name, in both modes. */

#define RD_FILE "__197_redefine.ktm"
#define RD_EXE  "__197_redefine.exe"
#define RD_OUT  "__197_redefine.out"
/* `timeout` is coreutils; without it the command just runs */
#define RD_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
#define RD_KTM RD_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U0 CleanupRedefineTest(U0)
{
  System("rm -f " RD_FILE " " RD_EXE " " RD_OUT " ./a.out");
}

/* Compile `source` ahead of time and run it (`jit` FALSE), or run it in
 * the JIT. It must exit with `status` and its output contain `want1`
 * and `want2` (NULL to skip). */
Bool RunRedefine(Bool jit, U8 *source, I64 status, U8 *want1, U8 *want2)
{
  U8 *out;
  I64 res;
  Bool ok = TRUE;

  CleanupRedefineTest;
  if (!FileWrite(RD_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  if (jit) {
    res = System(RD_KTM "-jit " RD_FILE " > " RD_OUT " 2>&1");
  } else {
    res = System(RD_KTM RD_FILE " -o " RD_EXE " > " RD_OUT " 2>&1 && " RD_TIMEOUT "./" RD_EXE " >> " RD_OUT " 2>&1");
  }
  out = FileRead(RD_OUT);
  if (!out) {
    CleanupRedefineTest;
    return FALSE;
  }
  if (res != status) ok = FALSE;
  if (want1 && !strstr(out, want1)) ok = FALSE;
  if (want2 && !strstr(out, want2)) ok = FALSE;
  if (!ok) "jit=%d: exit status %d\n%s\n", jit, res, out;
  Free(out);
  CleanupRedefineTest;
  return ok;
}

/* Own Now() plus other library functions (StrLen, MPrintDate) */
U8 *rd_same_sig =
"CDate Now()
{
  return 42;
}

U0 Main()
{
  \"now=%d len=%d\\n\", Now, StrLen(\"abc\");
  U8 *date = MPrintDate(Now);
  \"date=%s\\n\", date;
  Free(date);
}
";

U8 *rd_lib_conflict =
"U8 *Now()
{
  return \"x\";
}

U0 Main()
{
  \"%s %d\\n\", Now, StrLen(\"abc\");
}
";

U0 Main()
{
  "Test - redefine a library function: ";
  I64 correct = 0, tests = 0;

  /* Same signature: links in AOT, and both modes call the program's */
  tests++;
  if (RunRedefine(FALSE, rd_same_sig, 0, "now=42 len=3", "date=01/02/0000")) correct++;
  tests++;
  if (RunRedefine(TRUE, rd_same_sig, 0, "now=42 len=3", "date=01/02/0000")) correct++;

  /* An integer return type spelt as the plain integer the intrinsic
   * class is (`I64 class CDate`) is the same signature */
  tests++;
  if (RunRedefine(FALSE,
"I64 Now() { return 7; }
U0 Main() { \"now=%d %d\\n\", Now, StrLen(\"ab\"); }
", 0, "now=7 2", NULL)) correct++;

  /* A different signature from the library's prototype is an error at
   * the definition's name, not a link failure */
  tests++;
  if (RunRedefine(FALSE, rd_lib_conflict, 1,
      "error: conflicting types for `Now`: `U8 *Now(U0);` does not match the earlier declaration `CDate Now(U0);` at ",
      "std.ktmh:")) correct++;
  tests++;
  if (RunRedefine(TRUE, rd_lib_conflict, 1, "conflicting types for `Now`",
                  RD_FILE ":1:5")) correct++;

  /* I64 vs U64 return differ (std.ktmh: `public U64 NowMilliseconds();`) */
  tests++;
  if (RunRedefine(TRUE,
"I64 NowMilliseconds() { return 0; }
U0 Main() { }
", 1, "conflicting types for `NowMilliseconds`", NULL)) correct++;

  /* Two prototypes in the program that disagree */
  tests++;
  if (RunRedefine(TRUE,
"I64 Twice(I64 a);
I64 Twice(U8 *a);
U0 Main() { }
", 1, "conflicting types for `Twice`: `I64 Twice(U8 *a);`", RD_FILE ":2:5")) correct++;

  /* A definition that disagrees with the program's prototype on a
   * parameter, on varargs, or the number of parameters */
  tests++;
  if (RunRedefine(FALSE,
"I64 Add(I64 a, I64 b);
I64 Add(I64 a, F64 b) { return a; }
U0 Main() { }
", 1, "conflicting types for `Add`", RD_FILE ":2:5")) correct++;
  tests++;
  if (RunRedefine(TRUE,
"U0 Log(U8 *fmt, ...);
U0 Log(U8 *fmt) { }
U0 Main() { }
", 1, "conflicting types for `Log`", NULL)) correct++;
  tests++;
  if (RunRedefine(TRUE,
"I64 One(I64 a);
I64 One(I64 a, I64 b) { return a + b; }
U0 Main() { }
", 1, "conflicting types for `One`", NULL)) correct++;

  /* A prototype after the definition that disagrees */
  tests++;
  if (RunRedefine(TRUE,
"I64 Late() { return 1; }
F64 Late();
U0 Main() { }
", 1, "conflicting types for `Late`", RD_FILE ":2:5")) correct++;

  /* Matching prototypes, repeated, with a default argument, an array
   * parameter for a pointer one and varargs, are all fine */
  tests++;
  if (RunRedefine(FALSE,
"class Pt { I64 x; };
I64 Sum(I64 *v, I64 n=2);
I64 Sum(I64 *v, I64 n=2);
I64 Sum(I64 v[], I64 n) { I64 s = 0, i; for (i = 0; i < n; i++) s += v[i]; return s; }
I64 Sum(I64 *v, I64 n);
I64 GetX(Pt *p);
I64 GetX(Pt *p) { return p->x; }
U0 Say(U8 *fmt, ...);
U0 Say(U8 *fmt, ...) { \"said %d\\n\", argc; }
U0 Main() {
  I64 v[2] = {3, 4};
  Pt p;
  p.x = 9;
  Say(\"x\", 1, 2);
  \"sum=%d x=%d\\n\", Sum(v, 2), GetX(&p);
}
", 0, "said 2", "sum=7 x=9")) correct++;

  PrintResult(correct, tests);
  "====\n";
}
