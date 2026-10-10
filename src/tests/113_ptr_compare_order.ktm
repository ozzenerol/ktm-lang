#include "testhelper.ktm"

/* A binary op with only its right operand a pointer had its operands
 * swapped (meant to turn `1 + p` into `p + 1`), so `z < p` ran as
 * `p < z` and `5 - p` as `p - 5`. Only `+` may be reordered; `int - ptr`
 * is now a compile error, as in C. */

#define PTR_CMP_TEST_FILE "__113_ptr_compare_fail.ktm"
#define PTR_CMP_TEST_OUT  "__113_ptr_compare_fail.out"

U0 CleanupPtrCmpTest(U0)
{
  Rm(PTR_CMP_TEST_FILE);
  Rm(PTR_CMP_TEST_OUT);
  Rm("./a.out");
}

Bool CompileFailsWith(U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;

  CleanupPtrCmpTest;
  if (!FileWrite(PTR_CMP_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " PTR_CMP_TEST_FILE " > " PTR_CMP_TEST_OUT " 2>&1");
  if (res == 0) {
    CleanupPtrCmpTest;
    return FALSE;
  }
  out = FileRead(PTR_CMP_TEST_OUT);
  if (!out) {
    CleanupPtrCmpTest;
    return FALSE;
  }
  if (!strstr(out, expected)) {
    "%s\n", out;
    Free(out);
    CleanupPtrCmpTest;
    return FALSE;
  }
  Free(out);
  CleanupPtrCmpTest;
  return TRUE;
}

I64 g_order = 0;
I64 Mark(I64 v) { g_order = g_order * 10 + v; return v; }

I32 Main()
{
  "Test - Pointer comparison operand order: \n";
  I64 tests = 22;
  I64 correct = 0;

  U8 buf[4];
  U8 *q = buf;
  I64 z = 0;
  I64 a = q;
  I64 hi = q + 100;

  /* int on the left, pointer on the right */
  if ((z < q) == 1 && (z > q) == 0) correct++; else "Failed - z < q / z > q\n";
  if ((z <= q) == 1 && (z >= q) == 0) correct++; else "Failed - z <= q / z >= q\n";
  if ((z == q) == 0 && (z != q) == 1) correct++; else "Failed - z == q / z != q\n";
  if ((hi > q) == 1 && (hi < q) == 0) correct++; else "Failed - hi > q\n";
  if ((hi >= q) == 1 && (hi <= q) == 0) correct++; else "Failed - hi >= q\n";

  /* pointer on the left (unchanged) */
  if ((q > z) == 1 && (q < z) == 0 && (q >= z) == 1 && (q <= z) == 0) correct++;
  else "Failed - q OP z\n";

  /* I64 holding the same address */
  if ((a == q) && !(a != q) && (a <= q) && (a >= q) && !(a < q) && !(a > q)) correct++;
  else "Failed - a OP q\n";
  if ((a < q + 1) && !(a > q + 1) && (a + 1 > q) && !(a + 1 < q)) correct++;
  else "Failed - a OP q +- 1\n";

  /* NULL / 0 on either side */
  U8 *n = NULL;
  if ((NULL == n) && (0 == n) && (n == NULL) && (n == 0)) correct++; else "Failed - NULL == n\n";
  if ((NULL != q) && (0 != q) && (0 < q) && !(0 > q)) correct++; else "Failed - 0 OP q\n";

  /* two pointers */
  U8 *q2 = q + 2;
  if ((q < q2) && !(q > q2) && (q2 > q) && (q != q2) && (q2 - q == 2)) correct++;
  else "Failed - q OP q2\n";

  /* chained range comparison with the pointer in the middle */
  if (z < q < hi) correct++; else "Failed - z < q < hi\n";
  if (!(hi < q < hi)) correct++; else "Failed - hi < q < hi\n";

  /* an array on the right */
  I64 arr[4];
  arr[0] = 10; arr[1] = 11; arr[2] = 12; arr[3] = 13;
  if ((z < arr) && !(z > arr)) correct++; else "Failed - z < arr\n";

  /* `+` is still reordered and scaled whichever side the pointer is on */
  I64 *w = arr;
  I64 *w1 = 1 + w;
  I64 *w2 = w + 1;
  if (w1 == w2 && w1(I64) - w(I64) == 8 && *w1 == 11) correct++; else "Failed - 1 + w\n";
  I64 i = 3;
  if (*(i + arr) == 13 && *(arr + i) == 13) correct++; else "Failed - i + arr\n";
  if (*(w2 - 1) == 10 && (w2 - w) == 1) correct++; else "Failed - w2 - 1\n";

  /* `&&` / `||` keep their evaluation order with a pointer on the right */
  g_order = 0;
  if (Mark(1) && Mark(2) + q) correct++;
  if (g_order == 12) correct++; else "Failed - && order %d\n", g_order;

  /* int - pointer is rejected, as in C */
  Bool ok1 = CompileFailsWith(
"void Main() { i64 *w = NULL; i64 *r = 5 - w; \"%d\\n\", r; }
", "`i64 - i64 *` is invalid");
  Bool ok2 = CompileFailsWith(
"void Main() { i64 arr[4]; i64 i = 1; i64 *r = i - arr; \"%d\\n\", r; }
", "is invalid");
  if (ok1) correct++; else "Failed - 5 - w compiled\n";
  if (ok2) correct++; else "Failed - i - arr compiled\n";

  /* pointer - int still compiles */
  if (q2 - 2 == q) correct++; else "Failed - q2 - 2\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
