#include "testhelper.ktm"

/* A `\` at the end of a line did not continue it, so a #define
 * written over several lines ended at the first and the rest was
 * lexed as code, with confusing errors. Backslash-newline now joins
 * the lines, as in C. */

#define SUM (1 + \
  2 + \
  3)

#define GREETING \
  "hello"

#define HALF (1.0 \
 / 2)

#if SUM == 6 && \
    HALF == 0.5
I64 g_if = 1;
#else
I64 g_if = 0;
#endif

I64 g_after = \
  7;

I64 Add(I64 a, \
        I64 b)
{
  return a + b;
}

#define CL_FILE "__173_define_line_continuation.ktm"
#define CL_OUT  "__173_define_line_continuation.out"

/* Compile `source`: true if it fails and prints `expected` */
Bool FailsWith(U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;
  Bool good = TRUE;

  Rm(CL_FILE);
  if (!FileWrite(CL_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " CL_FILE " > " CL_OUT " 2>&1");
  out = FileRead(CL_OUT);
  if (res == 0 || !out || !strstr(out, expected)) good = FALSE;
  if (!good && out) "%s\n", out;
  if (out) Free(out);
  Rm(CL_FILE);
  Rm(CL_OUT);
  return good;
}

I32 Main()
{
  I64 correct = 0, tests = 0;

  "Test - line continuation in #define: ";

  tests++; if (SUM == 6) correct++;
  tests++; if (!StrCmp(GREETING, "hello")) correct++;
  tests++; if (HALF == 0.5) correct++;
  tests++; if (g_if == 1) correct++;
  tests++; if (g_after == 7) correct++;
  tests++; if (Add(2, 3) == 5) correct++;

  /* an error on a continued line is reported on that line */
  tests++;
  if (FailsWith("I64 x = 1 + \\\n  y;\nU0 Main() {}\n",
                CL_FILE ":2:3")) correct++;
  /* a `\` not at the end of a line is still an error */
  tests++;
  if (FailsWith("I64 x = 1 \\ 2;\nU0 Main() {}\n",
                "Unexpected character `\\`")) correct++;
  /* lines after a continued #define keep their numbers */
  tests++;
  if (FailsWith("#define A (1 + \\\n 2)\nI64 x = A;\nI64 y = z;\nU0 Main() {}\n",
                CL_FILE ":4:9")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
