#include "testhelper.ktm"

/* An error inside a brace initialiser is reported once. The parser's
 * recovery used to stop at the `}` of the innermost list and report the
 * rest of the initialiser, or the next statement, as a second error
 * (e.g. `Identifier expected: got TK_STR "ok\n"`). Each source below has
 * a second, independent error further down, which must be reported too:
 * that shows the following code is parsed normally. */

#define INIT_RECOVERY_FILE "__140_initialiser_error_recovery.ktm"
#define INIT_RECOVERY_OUT  "__140_initialiser_error_recovery.out"

#define MSG_FLOAT_STR "The `+` operator cannot be applied to a float type with a string literal of array type"

U0 CleanupInitRecoveryTest(U0)
{
  Rm(INIT_RECOVERY_FILE);
  Rm(INIT_RECOVERY_OUT);
  Rm("./a.out");
}

I64 CountErrors(U8 *out)
{
  I64 n = 0;
  U8 *p = out;
  while ((p = strstr(p, "error: "))) {
    n++;
    p += 7;
  }
  return n;
}

/* Compile `source`: ktm must exit with status 1 and print exactly two
 * errors, `m1` then `m2`, at `loc1` and `loc2`. */
Bool CompileFailsTwice(U8 *source, U8 *m1, U8 *loc1, U8 *m2, U8 *loc2)
{
  U8 *out, *first, *second;
  I64 res;
  Bool ok;

  CleanupInitRecoveryTest;

  if (!FileWrite(INIT_RECOVERY_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " INIT_RECOVERY_FILE " > " INIT_RECOVERY_OUT " 2>&1");

  out = FileRead(INIT_RECOVERY_OUT);
  if (!out) {
    CleanupInitRecoveryTest;
    return FALSE;
  }

  ok = res == 1 && CountErrors(out) == 2;
  first = strstr(out, "error: ");
  second = NULL;
  if (first) second = strstr(first + 7, "error: ");
  if (!first || !second) {
    ok = FALSE;
  } else {
    if (StrNCmp(first + 7, m1, StrLen(m1))) ok = FALSE;
    if (StrNCmp(second + 7, m2, StrLen(m2))) ok = FALSE;
    /* Each location is in its own error's header. */
    U8 *loc = strstr(first, loc1);
    if (!loc || loc > second) ok = FALSE;
    if (!strstr(second, loc2)) ok = FALSE;
  }
  if (!ok) "status %d\n%s\n", res, out;

  Free(out);
  CleanupInitRecoveryTest;
  return ok;
}

Bool TestClassTooManyThenString(U0)
{
  /* The reported repro: the next statement is a string. */
  return CompileFailsTwice(
"class Foo { I64 a; I64 b; };
U0 Main() { Foo f = {1, 2, 3}; \"ok\\n\"; }
U0 Other() { I64 z = 2.5 + \"t\"; }
", "More initialisers than class fields for class: Foo", ":2:29",
   MSG_FLOAT_STR, ":3:26");
}

Bool TestLocalLastElement(U0)
{
  /* The reported repro: the next statement is a declaration. */
  return CompileFailsTwice(
"U0 Main() {
  I64 a[2] = {1, 2.5 + \"s\"};
  I64 u = 3;
  I64 z = 2.5 + \"t\";
}
", MSG_FLOAT_STR, ":2:22", MSG_FLOAT_STR, ":4:15");
}

Bool TestLocalNested(U0)
{
  return CompileFailsTwice(
"U0 Main() {
  I64 m[2][2] = {{1, 2}, {3, 2.5 + \"s\"}};
  I64 k = 1;
  I64 z = 2.5 + \"t\";
}
", MSG_FLOAT_STR, ":2:34", MSG_FLOAT_STR, ":4:15");
}

Bool TestLocalNestedFirstRow(U0)
{
  /* The error is in the first inner list; the rest is skipped. */
  return CompileFailsTwice(
"U0 Main() {
  I64 c[2][2][2] = {{{1, 2.5 + \"s\"}, {3, 4}}, {{5, 6}, {7, 8}}};
  \"ok\\n\";
  I64 z = 2.5 + \"t\";
}
", MSG_FLOAT_STR, ":2:30", MSG_FLOAT_STR, ":4:15");
}

Bool TestGlobalNested(U0)
{
  return CompileFailsTwice(
"I64 g[2][2] = {{1, 2.5 + \"s\"}, {3, 4}};
I64 h = 5;
U0 Main() { I64 z = 2.5 + \"t\"; }
", MSG_FLOAT_STR, ":1:24", MSG_FLOAT_STR, ":3:25");
}

Bool TestGlobalClass(U0)
{
  return CompileFailsTwice(
"class Foo { I64 a; I64 b; };
Foo f = {1, 2, 3};
I64 h = 5;
I64 z = 2.5 + \"t\";
", "More initialisers than class fields for class: Foo", ":2:17",
   MSG_FLOAT_STR, ":4:13");
}

Bool TestClassArrayField(U0)
{
  /* Error inside the nested list of an array field. */
  return CompileFailsTwice(
"class Bar { I64 x; I64 y[2]; };
U0 Main() {
  Bar b = {1, {2, .y}};
  I64 k = 1;
  I64 z = 2.5 + \"t\";
}
", "Designated initialisers", ":3:20", MSG_FLOAT_STR, ":5:15");
}

Bool TestMissingOperand(U0)
{
  /* The bad expression runs into the closing `}`. */
  return CompileFailsTwice(
"U0 Main() {
  I64 a[3] = {1, 2 + };
  I64 k = 1;
  I64 z = 2.5 + \"t\";
}
", "Second operand missing", ":2:", MSG_FLOAT_STR, ":4:15");
}

Bool TestMoreDeclaratorsAfter(U0)
{
  /* The rest of the statement (`, b = 4`) is skipped with it. */
  return CompileFailsTwice(
"U0 Main() {
  I64 a[2] = {1, 2.5 + \"s\"}, b = 4;
  I64 k = 1;
  I64 z = 2.5 + \"t\";
}
", MSG_FLOAT_STR, ":2:22", MSG_FLOAT_STR, ":4:15");
}

Bool TestRowTooLong(U0)
{
  /* The overflow is found after the row's `}`; the rest of the
   * initialiser (`, {4, 5}}`) is skipped too. */
  return CompileFailsTwice(
"U0 Main() {
  I64 m[2][2] = {{1, 2, 3}, {4, 5}};
  I64 k = 1;
  I64 z = 2.5 + \"t\";
}
", "Invalid array initializer: expected 2 items but got 3", ":2:26",
   MSG_FLOAT_STR, ":4:15");
}

Bool TestStringTooLong(U0)
{
  return CompileFailsTwice(
"U8 s[2][3] = {\"abcdef\", \"x\"};
I64 h = 5;
U0 Main() { I64 z = 2.5 + \"t\"; }
", "Invalid array initializer: expected 3 items but got 7", ":1:15",
   MSG_FLOAT_STR, ":3:25");
}

I32 Main()
{
  "Test - Initialiser error recovery: ";
  I64 tests = 11;
  I64 correct = 0;

  if (TestClassTooManyThenString()) correct++;
  else "Failed - TestClassTooManyThenString()\n";

  if (TestLocalLastElement()) correct++;
  else "Failed - TestLocalLastElement()\n";

  if (TestLocalNested()) correct++;
  else "Failed - TestLocalNested()\n";

  if (TestLocalNestedFirstRow()) correct++;
  else "Failed - TestLocalNestedFirstRow()\n";

  if (TestGlobalNested()) correct++;
  else "Failed - TestGlobalNested()\n";

  if (TestGlobalClass()) correct++;
  else "Failed - TestGlobalClass()\n";

  if (TestClassArrayField()) correct++;
  else "Failed - TestClassArrayField()\n";

  if (TestMissingOperand()) correct++;
  else "Failed - TestMissingOperand()\n";

  if (TestMoreDeclaratorsAfter()) correct++;
  else "Failed - TestMoreDeclaratorsAfter()\n";

  if (TestRowTooLong()) correct++;
  else "Failed - TestRowTooLong()\n";

  if (TestStringTooLong()) correct++;
  else "Failed - TestStringTooLong()\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
