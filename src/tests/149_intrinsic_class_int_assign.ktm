#include "testhelper.ktm"

/* An intrinsic class (`I64 class CDate`, `U16 class`, `I32 class`) is an
 * integer underneath: `I64 x = date;` or `x += date;` warned
 * "Incompatible types 'CDate' is not assignable to type 'I64'" (integer to
 * intrinsic class was already silent). A compound assignment
 * also put the warning on the previous `=` in the source - for `y += d;`
 * that was the `CDate d = 5;` line above it. */

#define IC_TEST_FILE "__149_intrinsic_class.ktm"
#define IC_TEST_OUT  "__149_intrinsic_class.out"

U0 CleanupIcTest(U0)
{
  Rm(IC_TEST_FILE);
  Rm(IC_TEST_OUT);
}

/* Runs `source` with the JIT; the combined stdout and stderr, or NULL. */
U8 *RunKtm(U8 *source)
{
  CleanupIcTest;
  if (!FileWrite(IC_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return NULL;
  }
  System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " IC_TEST_FILE " > " IC_TEST_OUT " 2>&1");
  U8 *out = FileRead(IC_TEST_OUT);
  CleanupIcTest;
  return out;
}

Bool Contains(U8 *out, U8 *want)
{
  if (out && strstr(out, want)) return TRUE;
  "\nexpected `%s` in:\n%s\n", want, out;
  return FALSE;
}

Bool Lacks(U8 *out, U8 *unwanted)
{
  if (out && !strstr(out, unwanted)) return TRUE;
  "\nunexpected `%s` in:\n%s\n", unwanted, out;
  return FALSE;
}

U8 *intrinsic_src =
"U16 class W { U8 lo, hi; };
I32 class D { U16 a, b; };
class S { CDate d; I64 x; };

CDate Ret() { return 5; }
I64 Back(CDate d) { return d; }
U0 Take(CDate d) { \"take %d\\n\", d; }

U0 Main() {
  CDate d = 5;
  d = 6;
  d += 1;
  I64 y = d;
  y = d;
  y += d;
  y = y - d;
  I64 arr[2];
  arr[1] = d;
  S s;
  s.d = 9;
  s.x = s.d;
  W w = 0x1234;
  U16 u = w;
  I64 wy = w;
  w = u + 1;
  D dd = 0x10002;
  I32 i = dd;
  dd = i + 1;
  d = w;
  w = d;
  Take(3);
  \"%d %d %d %d %x %x %x %x %x %d %d\\n\",
    d, y, arr[1], s.x, u, wy, w, i, dd, Ret, Back(7);
}
";

U8 *where_src =
"class P { I64 a, b; };
P g;
P *Mk(I64 a, I64 b, I64 c) { return &g; }
U0 Main() {
  U8 *s = \"x\";
  CDate d = 5;
  I64 y = 1;
  y += d;
  P p;
  I64 z = p;
  F64 f = d;
  y = 2; s =
    Mk(4, 5, 6);
  \"%d\\n\", y;
}
";

I32 Main()
{
  I64 correct = 0, tests = 8;

  "Test - integer <-> intrinsic class assignment: ";

  U8 *out = RunKtm(intrinsic_src);
  /* No diagnostic at all, and the values come through. */
  if (Lacks(out, "warning") && Lacks(out, "Incompatible")) correct++;
  if (Contains(out, "take 3\n4661 7 7 9 1234 1234 1235 10002 10003 5 7\n")) correct++;
  Free(out);

  out = RunKtm(where_src);
  /* `y += d` is fine and no longer reported against `CDate d = 5;`. */
  if (Lacks(out, "'CDate' is not assignable to type 'I64'") &&
      Lacks(out, "CDate d = 5;")) correct++;
  /* A real class still can't become an integer (an error since test
   * 222)... */
  if (Contains(out, "`I64 = P` is invalid")) correct++;
  if (Contains(out, "I64 z = p;")) correct++;
  /* An intrinsic class converts to a float like an integer, silently. */
  if (Lacks(out, "'CDate' is not assignable to type 'F64'")) correct++;
  /* ...and the warning is on the line with the `=`, from the `=`. */
  if (Contains(out, "Incompatible types 'P *' is not assignable to type 'U8 *'")) correct++;
  if (Contains(out, "y = 2; s =\n") && Lacks(out, "Mk(4, 5, 6);\n")) correct++;
  Free(out);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
