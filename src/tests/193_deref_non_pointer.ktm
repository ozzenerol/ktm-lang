#include "testhelper.ktm"

/* `I64 d = 1 +* 2;` crashed ktm. It parses as `1 + (*2)` and the
 * unary `*` on an integer gave the expression no type (the "Unary type
 * is being assigned as NULL" warning), which segfaulted later on. `*`
 * on something that is not a pointer or an array is now an error at the
 * `*`. Each source must fail with exit status 1 (not a signal) and the
 * expected message; real two operator expressions still work. */

#define DEREF_TEST_SRC "./ktm-193-deref.ktm"
#define DEREF_TEST_OUT "./ktm-193-deref.out"
#define DEREF_TEST_BIN "./ktm-193-deref.bin"
/* A single literal: adjacent strings don't concatenate inside #define */
#define DEREF_KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U0 CleanupDerefTest(U0)
{
  System("rm -f " DEREF_TEST_SRC " " DEREF_TEST_OUT " " DEREF_TEST_BIN);
}

/* ktm exits 1 and its output has `e1` and `e2` (NULL to skip) */
Bool FailsWith(U8 *source, U8 *e1, U8 *e2 = NULL)
{
  U8 *out;
  I64 res;
  Bool ok;

  CleanupDerefTest;
  FileWrite(DEREF_TEST_SRC, source, StrLen(source),
            O_CREAT | O_RDWR | O_TRUNC);
  res = System(DEREF_KTM DEREF_TEST_SRC " -o " DEREF_TEST_BIN " > "
               DEREF_TEST_OUT " 2>&1; test $? -eq 1");
  out = FileRead(DEREF_TEST_OUT);
  ok = res == 0 && out && strstr(out, e1) && (!e2 || strstr(out, e2));
  if (!ok) "got (%d): '%s'\n", res, out;
  Free(out);
  CleanupDerefTest;
  return ok;
}

/* Compiles and prints exactly `expected` */
Bool Prints(U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;
  Bool ok;

  CleanupDerefTest;
  FileWrite(DEREF_TEST_SRC, source, StrLen(source),
            O_CREAT | O_RDWR | O_TRUNC);
  res = System(DEREF_KTM DEREF_TEST_SRC " -o " DEREF_TEST_BIN " && "
               DEREF_TEST_BIN " > " DEREF_TEST_OUT " 2>&1");
  out = FileRead(DEREF_TEST_OUT);
  ok = res == 0 && out && !StrCmp(out, expected);
  if (!ok) "got (%d): '%s'\n", res, out;
  Free(out);
  CleanupDerefTest;
  return ok;
}

#define NOT_PTR "The `*` operator cannot be applied to"

I32 Main()
{
  "Test - deref of a non-pointer: ";
  I64 tests = 0;
  I64 correct = 0;

  /* The repro, at the top level, reported at the `*` (column 12) */
  tests++;
  if (FailsWith("I64 d = 1 +* 2;\n", NOT_PTR, "deref.ktm:1:12")) correct++;
  else "Failed - the repro\n";

  /* Inside a function, other operators and other non-pointers */
  tests++;
  if (FailsWith("U0 Main()\n{\n  I64 d = 1 -* 2;\n}\nMain;\n",
                NOT_PTR, "deref.ktm:3:14")) correct++;
  else "Failed - 1 -* 2\n";
  tests++;
  if (FailsWith("U0 Main()\n{\n  I64 x = 3;\n  I64 d = x ** x;\n}\nMain;\n",
                NOT_PTR, "integer")) correct++;
  else "Failed - x ** x\n";
  tests++;
  if (FailsWith("U0 Main()\n{\n  F64 f = 1.5;\n  F64 d = *f;\n}\nMain;\n",
                NOT_PTR, "float")) correct++;
  else "Failed - *f\n";
  tests++;
  if (FailsWith("U0 Main()\n{\n  I64 a[2];\n  a[*1] = 0;\n}\nMain;\n",
                NOT_PTR)) correct++;
  else "Failed - a[*1]\n";

  /* Two binary operators in a row and a missing operand stay errors */
  tests++;
  if (FailsWith("U0 Main()\n{\n  I64 d = 1 */ 2;\n}\nMain;\n",
                "Second operand missing")) correct++;
  else "Failed - 1 */ 2\n";
  tests++;
  if (FailsWith("U0 Main()\n{\n  I64 d = 1 + / 2;\n}\nMain;\n",
                "Second operand missing")) correct++;
  else "Failed - 1 + / 2\n";
  tests++;
  if (FailsWith("U0 Main()\n{\n  I64 x;\n  x = * ;\n}\nMain;\n",
                "Expected expression after unary")) correct++;
  else "Failed - x = * ;\n";
  tests++;
  if (FailsWith("U0 Main()\n{\n  I64 a[3];\n  a[1 +] = 2;\n}\nMain;\n",
                "Second operand missing")) correct++;
  else "Failed - a[1 +]\n";

  /* Unary operators after a binary one are still fine */
  tests++;
  if (Prints("U0 Main()\n{\n  I64 x = 5, a[2];\n  I64 *p = &x;\n"
             "  a[0] = 9;\n  I64 **pp = &p;\n"
             "  \"%d %d %d %d %d %d\\n\", 1 + *p, 1 - -2, 1 + &x - &x,\n"
             "    2 * *p, 1 + *a, 1 + **pp;\n}\nMain;\n",
             "6 3 1 10 10 6\n")) correct++;
  else "Failed - valid expressions\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
