#include "testhelper.ktm"

/* Places that need an expression accepted none: `while ()` and
 * `do ... while ();` compiled to nothing, `if ()`, `switch ()` and a
 * `case` range missing an end crashed. Each is now an error at the token
 * where the expression should be. The legal empty forms stay: a bare
 * `case:` (previous value + 1), `for (;;)` and `if (; cond)`.
 * `return;` in a function returning a value keeps its warning, now
 * without printing `'(null)' of type 'U0'`. */

#define EE_SRC "./ktm-228-ee.ktm"
#define EE_OUT "./ktm-228-ee.out"

U8 *Diagnose(U8 *source, I64 *_status)
{
  System("rm -f " EE_SRC " " EE_OUT);
  FileWrite(EE_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  *_status = System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-jit " EE_SRC " > " EE_OUT " 2>&1");
  U8 *out = FileRead(EE_OUT);
  System("rm -f " EE_SRC " " EE_OUT);
  return out;
}

/* ktm fails (status 1, not a crash) with `want` at `where` */
Bool Rejected(U8 *source, U8 *want, U8 *where)
{
  I64 status;
  U8 *out = Diagnose(source, &status);
  Bool ok = status == 1 && out && strstr(out, want) && strstr(out, where);
  if (!ok) "got (status %d): '%s'\n", status, out;
  Free(out);
  return ok;
}

U8 *legal_src =
"I64 F(I64 a=1, I64 b=2, I64 c=3) { return a + b + c; }
U0 Main()
{
  if (; 1) \"if \";
  I64 i = 0;
  for (;;) { if (++i > 2) break; }
  for (; i < 5; ) i++;
  switch (2) { case: \"zero \"; break; case: \"one \"; break; case: \"two \"; break; }
  \"%d %d\\n\", i, F(10,,20);
}
";

I32 Main()
{
  "Test - missing expressions are errors: ";
  I64 tests = 0, correct = 0, status;
  U8 *out;

  tests++;
  if (Rejected("U0 Main() { while () {} }\n",
               "Expected the `while` condition, got `)`", "228-ee.ktm:1:22\n")) correct++;
  tests++;
  if (Rejected("U0 Main() { do {} while (); }\n",
               "Expected the `while` condition, got `)`", "228-ee.ktm:1:27\n")) correct++;
  tests++;
  if (Rejected("U0 Main() { if () {} }\n",
               "Expected the `if` condition, got `)`", "228-ee.ktm:1:19\n")) correct++;
  tests++;
  if (Rejected("U0 Main() { if (I64 a = 1; ) {} }\n",
               "Expected the `if` condition, got `)`", "228-ee.ktm:1:30\n")) correct++;
  tests++;
  if (Rejected("U0 Main() { if (I64 a = ) {} }\n",
               "Expected an initial value, got `)`", "228-ee.ktm:1:27\n")) correct++;
  tests++;
  if (Rejected("U0 Main() { switch () { case 1: break; } }\n",
               "Expected the `switch` value, got `)`", "228-ee.ktm:1:23\n")) correct++;
  tests++;
  if (Rejected("U0 Main() { switch [] { case 1: break; } }\n",
               "Expected the `switch` value, got `]`", "228-ee.ktm:1:23\n")) correct++;
  tests++;
  if (Rejected("U0 Main() { switch (1) { case 1 ... : break; } }\n",
               "Expected the end of the `case` range, got `:`", "228-ee.ktm:1:39\n")) correct++;
  tests++;
  if (Rejected("U0 Main() { switch (1) { case ... 3: break; } }\n",
               "Expected a `case` value or `:`, got `...`", "228-ee.ktm:1:35\n")) correct++;
  tests++;
  if (Rejected("U0 Main() { try { throw(); } catch { } }\n",
               "Expected the value to `throw`, got `)`", "228-ee.ktm:1:26\n")) correct++;
  tests++;
  if (Rejected("U0 Main() { (); }\n",
               "Expected an expression between `(` and `)`", "228-ee.ktm:1:15\n")) correct++;

  /* `return;` where a value is expected: still a warning, now readable */
  tests++;
  out = Diagnose("I64 F() { return; }\nU0 Main() { F; }\n", &status);
  if (status == 0 && out &&
      strstr(out, "`return` with no value, expected a value of type 'i64'") &&
      !strstr(out, "(null)")) correct++;
  else "got: '%s'\n", out;
  Free(out);

  /* The legal empty forms */
  tests++;
  out = Diagnose(legal_src, &status);
  if (status == 0 && out && !StrCmp(out, "if two 5 32\n")) correct++;
  else "got (status %d): '%s'\n", status, out;
  Free(out);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
