#include "testhelper.ktm"

/* A definition or prototype that doesn't match the standard library's
 * declaration is an error ("conflicting types"), but the check didn't
 * see `_extern _LABEL T Name(...)` bindings: on x86_64 `I64 Exit(I64 x)`
 * silently replaced the library's `U0 Exit(I64)`, while on arm64 (where
 * Exit is a plain prototype) the same file was rejected. Now both give
 * the same error. */

#define REDECL_FILE "__375_redecl.ktm"
#define REDECL_OUT  "__375_redecl.out"
#define REDECL_MSG  "conflicting types for `"

U0 CleanupRedecl(U0)
{
  Rm(REDECL_FILE);
  Rm(REDECL_OUT);
}

/* Compiles and runs `src` with the JIT. With `name` set, TRUE when the
 * compile fails with the conflicting-types error for it; with NULL, TRUE
 * when it compiles, runs and prints "ok" */
Bool Conflicts(U8 *src, U8 *name)
{
  U8 *out, want[64];
  I64 res;
  Bool ok;

  CleanupRedecl;
  if (!FileWrite(REDECL_FILE, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  res = System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " REDECL_FILE " > " REDECL_OUT " 2>&1");
  out = FileRead(REDECL_OUT);
  if (name) {
    StrPrint(want, REDECL_MSG "%s`", name);
    ok = res != 0 && out && strstr(out, want) != NULL;
  } else {
    ok = res == 0 && out && !StrCmp(out, "ok\n");
  }
  if (!ok && out) "%s\n", out;
  Free(out);
  CleanupRedecl;
  return ok;
}

I32 Main()
{
  "Test - extern redeclaration: ";
  I64 correct = 0, tests = 0;

  /* the repro: _extern on x86_64, a prototype on arm64 */
  tests++;
  if (Conflicts(
"I64 Exit(I64 x) { return x * 2; }
U0 Main() { \"%d\\n\", Exit(21); }
", "Exit")) correct++;

  /* _extern on every architecture */
  tests++;
  if (Conflicts(
"I64 MAlloc(I64 n) { return n; }
U0 Main() { MAlloc(3); }
", "MAlloc")) correct++;

  /* a prototype alone conflicts too */
  tests++;
  if (Conflicts(
"I64 ToLower(I64 ch);
U0 Main() { }
", "ToLower")) correct++;

  /* the same signature again is fine */
  tests++;
  if (Conflicts(
"U0 *MAlloc(U64 size);
U8 ToUpper(U8 ch);
U0 Main() { \"ok\\n\"; }
", NULL)) correct++;

  /* and the library's own functions work as before */
  tests++;
  if (Conflicts(
"U0 Main()
{
  U8 *p = MAlloc(4);
  if (p && ToUpper('o') == 'O' && IsSpace(' ') && Abs(-3) == 3) \"ok\\n\";
  Free(p);
  Exit(0);
}
", NULL)) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
