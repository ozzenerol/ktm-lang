#include "testhelper.ktm"

/* `a[i % n]` crashed ktm with a SIGFPE: the subscript is constant
 * folded to check its bounds, and the folder divided the 0 it uses
 * for each variable by the other. `/` and `%` now only fold when both
 * operands are constants, and an integer `/` or `%` by a constant 0 is
 * a located error instead of a crash, wherever it is. */

#define DIVZERO_TEST_SRC "./ktm-187-divzero.ktm"
#define DIVZERO_TEST_OUT "./ktm-187-divzero.out"
/* A single literal: adjacent strings don't concatenate inside #define */
#define DIVZERO_KTM "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U0 CleanupDivZeroTest(U0)
{
  System("rm -f " DIVZERO_TEST_SRC " " DIVZERO_TEST_OUT " a.out");
}

/* ktm must fail with exit status 1 (not a signal) and say `expected` */
Bool CompileFailsWith(U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;
  Bool ok;

  CleanupDivZeroTest;
  FileWrite(DIVZERO_TEST_SRC, source, StrLen(source),
            O_CREAT | O_RDWR | O_TRUNC);
  res = System(DIVZERO_KTM DIVZERO_TEST_SRC " > " DIVZERO_TEST_OUT " 2>&1; "
               "test $? -eq 1");
  out = FileRead(DIVZERO_TEST_OUT);
  ok = res == 0 && out && strstr(out, expected);
  if (!ok) "got (%d): '%s'\n", res, out;
  Free(out);
  CleanupDivZeroTest;
  return ok;
}

class Holder
{
  I64 vals[5];
};

I64 Idx(I64 i) { return i; }

I32 Main()
{
  "Test - subscript modulo/division folding: ";
  I64 tests = 0;
  I64 correct = 0;
  I64 a[4];
  I64 n = 3, i = 5, z = 0;
  U64 un = 3, ui = 7;
  Holder h;
  I64 b[3][4];

  /* The repro and its `/` twin */
  MemSet(a, 0, sizeof(a));
  a[i % n] = 1;
  tests++; if (a[2] == 1) correct++; else "Failed - a[i %% n]\n";
  a[i / n] = 7;
  tests++; if (a[1] == 7) correct++; else "Failed - a[i / n]\n";
  /* A 0 variable is still only known when it runs */
  a[z % n] = 9;
  tests++; if (a[0] == 9) correct++; else "Failed - a[z %% n]\n";
  a[z / n] = 10;
  tests++; if (a[0] == 10) correct++; else "Failed - a[z / n]\n";
  /* Unsigned operands, mixed constants, nested and compound uses */
  a[ui % un] = 11;
  tests++; if (a[1] == 11) correct++; else "Failed - a[ui %% un]\n";
  a[(i + 1) % n + 1] = 12;
  tests++; if (a[1] == 12) correct++; else "Failed - a[(i + 1) %% n + 1]\n";
  a[Idx(10) % n] = 13;
  tests++; if (a[1] == 13) correct++; else "Failed - a[Idx(10) %% n]\n";
  a[3 % n] = 14;
  tests++; if (a[0] == 14) correct++; else "Failed - a[3 %% n]\n";
  a[i % 4] = 15;
  tests++; if (a[1] == 15) correct++; else "Failed - a[i %% 4]\n";
  a[i % n] += 5;
  tests++; if (a[2] == 6) correct++; else "Failed - a[i %% n] += 5\n";
  h.vals[i % n] = 16;
  tests++; if (h.vals[2] == 16) correct++; else "Failed - h.vals[i %% n]\n";
  b[i % n][i / n] = 17;
  tests++; if (b[2][1] == 17) correct++; else "Failed - b[i %% n][i / n]\n";
  /* Constants still fold */
  a[7 % 4] = 18;
  tests++; if (a[3] == 18) correct++; else "Failed - a[7 %% 4]\n";

  /* A constant 0 divisor is an error, not a crash */
  tests++;
  if (CompileFailsWith("U0 Main() { I64 a[4]; a[5 % 0] = 1; } Main;",
                       "Modulo by zero")) correct++;
  else "Failed - a[5 %% 0]\n";
  tests++;
  if (CompileFailsWith("U0 Main() { I64 a[4]; a[5 / 0] = 1; } Main;",
                       "Division by zero")) correct++;
  else "Failed - a[5 / 0]\n";
  tests++;
  if (CompileFailsWith("U0 Main() { I64 x = 5, n = 2; x = x % (n - n + 0); "
                       "x /= 0; } Main;", "Division by zero")) correct++;
  else "Failed - x /= 0\n";
  tests++;
  if (CompileFailsWith("I64 g = 5 % (2 - 2);", "Modulo by zero")) correct++;
  else "Failed - global 5 %% (2 - 2)\n";
  tests++;
  if (CompileFailsWith("I64 arr[4 / 0];", "Division by zero")) correct++;
  else "Failed - I64 arr[4 / 0]\n";
  tests++;
  if (CompileFailsWith("U0 Main(I64 v) { switch (v) { case 1 / 0: break; } } "
                       "Main(1);", "Division by zero")) correct++;
  else "Failed - case 1 / 0\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
