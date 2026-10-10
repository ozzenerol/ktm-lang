#include "testhelper.ktm"

/* `I64 x = 1 2;` followed by `"ab" y;` in one function crashed ktm.
 * The error for `y` rewound up to 6 tokens looking for a punctuator
 * (`y` isn't one), out of its statement and back into the previous
 * one; the per-statement recovery then resynced from that earlier `;`,
 * parsed `"ab" y;` again and looped (a crash in a release build). The
 * error now points at `y`, recovery never resumes before the statement
 * that failed, and a stray `}` left at the top level by a recovery is
 * skipped instead of starting the next declaration. Each source must
 * fail with exit status 1 (not a signal or a hang) and report every
 * error. */

#define STMTERR_TEST_SRC "./ktm-188-stmterr.ktm"
#define STMTERR_TEST_OUT "./ktm-188-stmterr.out"
/* A single literal: adjacent strings don't concatenate inside #define */
#define STMTERR_KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U0 CleanupStmtErrTest(U0)
{
  System("rm -f " STMTERR_TEST_SRC " " STMTERR_TEST_OUT " a.out");
}

/* ktm exits 1 and its output has each of `e1`..`e3` (NULL to skip) */
Bool FailsWith(U8 *source, U8 *e1, U8 *e2 = NULL, U8 *e3 = NULL)
{
  U8 *out;
  I64 res;
  Bool ok;

  CleanupStmtErrTest;
  FileWrite(STMTERR_TEST_SRC, source, StrLen(source),
            O_CREAT | O_RDWR | O_TRUNC);
  res = System(STMTERR_KTM STMTERR_TEST_SRC " > " STMTERR_TEST_OUT " 2>&1; "
               "test $? -eq 1");
  out = FileRead(STMTERR_TEST_OUT);
  ok = res == 0 && out && strstr(out, e1) &&
       (!e2 || strstr(out, e2)) && (!e3 || strstr(out, e3));
  if (!ok) "got (%d): '%s'\n", res, out;
  Free(out);
  CleanupStmtErrTest;
  return ok;
}

I32 Main()
{
  "Test - consecutive statement errors: ";
  I64 tests = 0;
  I64 correct = 0;

  /* The repro: both errors, the second at `y` (line 4, column 8) */
  tests++;
  if (FailsWith("U0 Main()\n{\n  I64 x = 1 2;\n  \"ab\" y;\n}\nMain;\n",
                "Unexpected integer `2`", "stmterr.ktm:4:8",
                "Invalid identifier `y`")) correct++;
  else "Failed - the repro\n";

  /* Alone, `"ab" y;` is reported at `y`, not at `Main` */
  tests++;
  if (FailsWith("U0 Main()\n{\n  \"ab\" y;\n}\nMain;\n",
                "stmterr.ktm:3:8", "Invalid identifier `y`")) correct++;
  else "Failed - \"ab\" y; alone\n";

  /* Short statements before it: the rewind reached two back */
  tests++;
  if (FailsWith("U0 Main()\n{\n  I64 a;\n  a;\n  a;\n  \"ab\" y;\n"
                "  \"cd\" z;\n}\nMain;\n",
                "stmterr.ktm:6:8", "stmterr.ktm:7:8")) correct++;
  else "Failed - after short statements\n";

  /* In a nested block, at the top level, and with a call */
  tests++;
  if (FailsWith("U0 Main(I64 v)\n{\n  if (v) {\n    I64 x = 1 2;\n"
                "    \"ab\" y;\n  }\n}\nMain(1);\n",
                "stmterr.ktm:4:15", "stmterr.ktm:5:10")) correct++;
  else "Failed - nested block\n";
  tests++;
  if (FailsWith("I64 x = 1 2;\n\"ab\" y;\n",
                "stmterr.ktm:1:11", "stmterr.ktm:2:6")) correct++;
  else "Failed - top level\n";
  tests++;
  if (FailsWith("U0 F(I64 a, I64 b) {}\nU0 Main()\n{\n  F(1 2);\n"
                "  F(3 4);\n  I64 q = 5 6;\n}\nMain;\n",
                "stmterr.ktm:4:7", "stmterr.ktm:5:7", "stmterr.ktm:6:13")) correct++;
  else "Failed - calls\n";

  /* A stray `}` at the top level (here left by the recovery from a
   * bad `if`) no longer swallows the next declaration or crashes */
  tests++;
  if (FailsWith("if (1 { }\nclass Z;\nZ *p;\n",
                "in `if (...)`")) correct++;
  else "Failed - stray } before class\n";
  tests++;
  if (FailsWith("}\nclass Z;\n", "Identifier expected")) correct++;
  else "Failed - } class\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
