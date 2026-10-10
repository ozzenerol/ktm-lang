#include "testhelper.ktm"

/* A function name without `(` is a call with no arguments, wherever it
 * appears in an expression. Only `Function;`, `Function,` and `Function)`
 * were calls; `F == 3` or `F + 1` left a bare function in the tree and
 * crashed ktm ("Expr Unhandled AST kind: AST_FUNC"). `&F` stays the
 * function's address. */

class Pt
{
  I64 x, y;
};

I64 g_calls = 0;
Pt g_pt;

I64 F() { g_calls++; return 3; }
I64 G(I64 a = 4, I64 b = 5) { return a + b; }
F64 Half() { return 0.5; }
Pt *GetPt() { return &g_pt; }
U8 *Name() { return "abc"; }
I64 Twice(I64 x) { return x * 2; }

I32 Main()
{
  "Test - call without parentheses: ";
  I64 correct = 0, tests = 0;

  tests++; if (F == 3) correct++;
  tests++; I64 x = F + 1; if (x == 4) correct++;
  tests++; if (G * 2 == 18) correct++;
  tests++; if (-F == -3) correct++;
  tests++; if (!F == 0) correct++;
  tests++; if (F * F == 9) correct++;
  tests++; F64 h = Half * 4; if (h == 2.0) correct++;
  tests++;
  g_pt.x = 7; g_pt.y = 8;
  if (GetPt->x == 7 && GetPt->y == 8) correct++;
  tests++; if (Name[1] == 'b') correct++;
  tests++; if (Twice(F) == 6) correct++;
  tests++;
  I64 i = 0, n = 0;
  while (i < F) { i++; n++; }
  if (n == 3) correct++;
  tests++; x = 0; x += F; if (x == 3) correct++;

  /* The address and function pointers are unaffected */
  tests++;
  I64 (*fp)() = &F;
  if (fp() == 3 && fp == &F) correct++;
  tests++;
  I64 (*tw)(I64 v) = &Twice;
  if (tw(F) == 6) correct++;

  tests++; if (g_calls == 14) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
