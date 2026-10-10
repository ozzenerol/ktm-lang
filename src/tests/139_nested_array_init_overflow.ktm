#include "testhelper.ktm"

/* An inner list (or string) in a multi-dimensional array initialiser
 * must fit its dimension. ktm used to accept `I64 m[2][2] = {{1,2,3},..}`
 * silently and write the extra item over the next row. */

#define NESTED_INIT_FILE "__139_nested_array_init_overflow.ktm"
#define NESTED_INIT_OUT  "__139_nested_array_init_overflow.out"

#define MSG_2_3 "Invalid array initializer: expected 2 items but got 3"

/* Initialisers that fit are still accepted, globally and locally. */
I64 g_139_m[2][3] = {{1,2,3},{4,5,6}};
I64 g_139_unsized[][2] = {{1,2},{3,4},{5,6}};
I64 g_139_cube[2][2][2] = {{{1,2},{3,4}},{{5,6},{7,8}}};

U0 CleanupNestedInitTest(U0)
{
  Rm(NESTED_INIT_FILE);
  Rm(NESTED_INIT_OUT);
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

/* Compile `source`: ktm must exit with status 1 and print exactly one
 * error, `msg` at `loc`; the recovery skips the rest of the initialiser. */
Bool CompileFailsWith(U8 *source, U8 *msg, U8 *loc)
{
  U8 *out, *first;
  I64 res;
  Bool ok;

  CleanupNestedInitTest;

  if (!FileWrite(NESTED_INIT_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " NESTED_INIT_FILE " > " NESTED_INIT_OUT " 2>&1");

  out = FileRead(NESTED_INIT_OUT);
  if (!out) {
    CleanupNestedInitTest;
    return FALSE;
  }

  ok = res == 1 && CountErrors(out) == 1;
  first = strstr(out, "error: ");
  if (!first || StrNCmp(first + 7, msg, StrLen(msg))) ok = FALSE;
  else if (!strstr(first, loc)) ok = FALSE;
  if (!ok) "status %d\n%s\n", res, out;

  Free(out);
  CleanupNestedInitTest;
  return ok;
}

Bool TestLocalRowTooLong(U0)
{
  return CompileFailsWith(
"U0 Main() {
  I64 m[2][2] = {{1,2,3},{4,5}};
}
", MSG_2_3, "2:24");
}

Bool TestLocalLastRowTooLong(U0)
{
  return CompileFailsWith(
"U0 Main() {
  I64 m[2][2] = {{1,2},{3,4,5}};
}
", MSG_2_3, "2:30");
}

Bool TestGlobalRowTooLong(U0)
{
  return CompileFailsWith(
"I64 g[2][2] = {{1,2,3},{4,5}};
U0 Main() {}
", MSG_2_3, "1:22");
}

Bool TestTooManyRows(U0)
{
  return CompileFailsWith(
"U0 Main() {
  I64 m[2][2] = {{1},{2},{3}};
}
", MSG_2_3, "2:17");
}

Bool TestUnsizedOuterRowTooLong(U0)
{
  return CompileFailsWith(
"U0 Main() {
  I64 m[][2] = {{1,2},{3,4,5}};
}
", MSG_2_3, "2:29");
}

Bool TestCubeInnerTooLong(U0)
{
  return CompileFailsWith(
"U0 Main() {
  I64 c[2][2][2] = {{{1,2},{3,4}},{{5,6,7},{8,9}}};
}
", MSG_2_3, "2:42");
}

Bool TestCubeMiddleTooLong(U0)
{
  return CompileFailsWith(
"I64 c[2][2][2] = {{{1,2},{3,4},{5,6}},{{7,8},{9,10}}};
U0 Main() {}
", MSG_2_3, "1:37");
}

Bool TestStringTooLong(U0)
{
  /* "abc" needs 4 bytes with its NUL, like a top-level U8 buf[3]. */
  return CompileFailsWith(
"U0 Main() {
  U8 s[2][3] = {\"ab\",\"abc\"};
}
", "Invalid array initializer: expected 3 items but got 4", "2:22");
}

Bool TestGlobalStringTooLong(U0)
{
  return CompileFailsWith(
"U8 s[2][3] = {\"abcdef\",\"x\"};
U0 Main() {}
", "Invalid array initializer: expected 3 items but got 7", "1:15");
}

Bool TestValidInits(U0)
{
  I64 m[2][3] = {{1,2,3},{4,5,6}};
  I64 u[][2] = {{1,2},{3,4},{5,6}};
  I64 c[2][2][2] = {{{1,2},{3,4}},{{5,6},{7,8}}};
  U8 s[2][4] = {"abc","de"};
  Bool ok = TRUE;

  if (m[0][2] != 3 || m[1][0] != 4 || m[1][2] != 6) ok = FALSE;
  if (sizeof(u) != 48 || u[2][0] != 5 || u[2][1] != 6) ok = FALSE;
  if (c[0][1][1] != 4 || c[1][0][0] != 5 || c[1][1][1] != 8) ok = FALSE;
  if (StrCmp(s[0], "abc") || StrCmp(s[1], "de")) ok = FALSE;
  if (g_139_m[0][2] != 3 || g_139_m[1][0] != 4) ok = FALSE;
  if (sizeof(g_139_unsized) != 48 || g_139_unsized[2][1] != 6) ok = FALSE;
  if (g_139_cube[1][0][1] != 6 || g_139_cube[1][1][1] != 8) ok = FALSE;
  return ok;
}

I32 Main()
{
  "Test - Nested array initialiser overflow: ";
  I64 tests = 10;
  I64 correct = 0;

  if (TestLocalRowTooLong()) correct++;
  else "Failed - TestLocalRowTooLong()\n";

  if (TestLocalLastRowTooLong()) correct++;
  else "Failed - TestLocalLastRowTooLong()\n";

  if (TestGlobalRowTooLong()) correct++;
  else "Failed - TestGlobalRowTooLong()\n";

  if (TestTooManyRows()) correct++;
  else "Failed - TestTooManyRows()\n";

  if (TestUnsizedOuterRowTooLong()) correct++;
  else "Failed - TestUnsizedOuterRowTooLong()\n";

  if (TestCubeInnerTooLong()) correct++;
  else "Failed - TestCubeInnerTooLong()\n";

  if (TestCubeMiddleTooLong()) correct++;
  else "Failed - TestCubeMiddleTooLong()\n";

  if (TestStringTooLong()) correct++;
  else "Failed - TestStringTooLong()\n";

  if (TestGlobalStringTooLong()) correct++;
  else "Failed - TestGlobalStringTooLong()\n";

  if (TestValidInits()) correct++;
  else "Failed - TestValidInits()\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
