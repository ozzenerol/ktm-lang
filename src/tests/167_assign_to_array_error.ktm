#include "testhelper.ktm"

/* Assigning to an array (`arr = p`, `arr += 1`, `arr++`) was only an
 * "Incompatible types" warning, and `arr++` compiled silently. As in C
 * it is an error, reported at the operator. */

#define ARR_TEST_FILE "__167_assign_to_array.ktm"
#define ARR_TEST_OUT  "__167_assign_to_array.out"

U0 CleanupArrTest()
{
  Rm(ARR_TEST_FILE);
  Rm(ARR_TEST_OUT);
  Rm("./a.out");
}

/* Compiles `source`; TRUE if the result matches `should_fail` and, when
 * `expected` is given, the output contains it. */
Bool Compile(U8 *source, Bool should_fail, U8 *expected)
{
  U8 *out;
  I64 res;
  Bool ok;

  CleanupArrTest;
  if (!FileWrite(ARR_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " ARR_TEST_FILE " > " ARR_TEST_OUT " 2>&1");
  out = FileRead(ARR_TEST_OUT);
  if (!out) {
    CleanupArrTest;
    return FALSE;
  }
  ok = (res != 0) == should_fail;
  if (ok && expected && !strstr(out, expected)) ok = FALSE;
  if (!ok) "%s\n", out;
  Free(out);
  CleanupArrTest;
  return ok;
}

U8 *Wrap(U8 *stmt)
{
  return MStrPrint("class S { I64 a[2]; };\nI64 g[2];\n"
                   "U0 Main()\n{\n  I64 arr[2];\n  I64 *p = arr;\n  S s;\n"
                   "  %s\n}\n", stmt);
}

Bool Fails(U8 *stmt, U8 *expected)
{
  U8 *src = Wrap(stmt);
  Bool ok = Compile(src, TRUE, expected);
  Free(src);
  return ok;
}

I32 Main()
{
  "Test - assigning to an array is an error: ";
  I64 correct = 0, tests = 0;
  U8 *src;

  tests++; if (Fails("arr = arr + 1;", "Cannot assign to array `arr`")) correct++;
  tests++; if (Fails("arr += 1;", "Cannot assign to array `arr`")) correct++;
  tests++; if (Fails("arr = p;", "Cannot assign to array `arr`")) correct++;
  tests++; if (Fails("arr++;", "Cannot assign to array `arr`")) correct++;
  tests++; if (Fails("--arr;", "Cannot assign to array `arr`")) correct++;
  tests++; if (Fails("s.a = p;", "Cannot assign to array `s.a`")) correct++;
  tests++; if (Fails("g -= 1;", "Cannot assign to array `g`")) correct++;
  /* Reported at the operator, not as a type warning */
  tests++; if (Fails("arr = p;", ":8:7")) correct++;
  tests++; if (Fails("I64 x = 1;\n  arr = p;", ":9:7")) correct++;

  /* Elements, pointers and an array of arrays' rows are fine */
  src = Wrap("arr[0] = 1; arr[1] += 2; arr[0]++; p = arr; p += 1; s.a[1] = 3;\n"
             "  I64 grid[2][2]; grid[1][0] = 4; *arr = 5;");
  tests++; if (Compile(src, FALSE, NULL)) correct++;
  Free(src);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
