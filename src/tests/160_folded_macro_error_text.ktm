#include "testhelper.ktm"

/* A #define whose value is folded from several tokens (`(N + 1)`) got
 * an empty spelling, so an error on a use of it read "Unexpected
 * integer ``". The folded value is now spelled out. */

#define FM_TEST_FILE "__160_folded_macro_error_text.ktm"
#define FM_TEST_OUT  "__160_folded_macro_error_text.out"

U0 CleanupFoldedTest(U0)
{
  Rm(FM_TEST_FILE);
  Rm(FM_TEST_OUT);
}

/* Run `ktm -jit` on `source`: does it fail (or, with `ok`, pass) and
 * print `expected`? */
Bool JitOutputHas(U8 *source, Bool ok, U8 *expected)
{
  U8 *out;
  I64 res;
  Bool good = TRUE;

  CleanupFoldedTest;
  if (!FileWrite(FM_TEST_FILE, source, StrLen(source),
                 O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " FM_TEST_FILE " > " FM_TEST_OUT " 2>&1");
  if ((res == 0) != ok) good = FALSE;
  out = FileRead(FM_TEST_OUT);
  if (!out) {
    CleanupFoldedTest;
    return FALSE;
  }
  if (!strstr(out, expected)) good = FALSE;
  if (!good) "%s\n", out;
  Free(out);
  CleanupFoldedTest;
  return good;
}

/* the values are still right */
#define V_N 3
#define V_I (V_N + 1)
#define V_F (1.5 * 2)
#define V_NEG (0 - 5)
#define V_U (0xFFFFFFFFFFFFFFFF + 0)
#define V_C ('a' + 1)
#define V_S ("ab" "cd")

I32 Main()
{
  I64 correct = 0, tests = 0;

  "Test - error text of a folded macro: ";

  tests++;
  if (JitOutputHas("#define N 3\n#define T (N + 1)\nU0 Main() { I64 x = 2 T; }\n",
                   FALSE, "Unexpected integer `4`")) correct++;
  tests++;
  if (JitOutputHas("#define T (1.5 * 2)\nU0 Main() { F64 x = 2 T; }\n",
                   FALSE, "Unexpected float `3.0`")) correct++;
  tests++;
  if (JitOutputHas("#define T (1.0 / 4)\nU0 Main() { F64 x = 2 T; }\n",
                   FALSE, "Unexpected float `0.25`")) correct++;
  tests++;
  if (JitOutputHas("#define T (0 - 5)\nU0 Main() { I64 x = 2 T; }\n",
                   FALSE, "Unexpected integer `-5`")) correct++;
  tests++;
  if (JitOutputHas("#define T (0xFFFFFFFFFFFFFFFF + 0)\nU0 Main() { I64 x = 2 T; }\n",
                   FALSE, "`18446744073709551615`")) correct++;
  /* the spelling stays where it was right already */
  tests++;
  if (JitOutputHas("#define T 7\nU0 Main() { I64 x = 2 T; }\n",
                   FALSE, "Unexpected integer `7`")) correct++;

  tests++;
  if (V_I == 4 && V_NEG == -5 && V_C == 'b') correct++;
  tests++;
  if (V_F == 3.0) correct++;
  tests++;
  if (V_U(U64) == 0xFFFFFFFFFFFFFFFF) correct++;
  tests++;
  if (!StrCmp(V_S, "abcd")) correct++;

  CleanupFoldedTest;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
