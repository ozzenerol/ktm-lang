#include "testhelper.ktm"

/* A float used as a truth value must be compared against 0.0, as in C
 * (-0.0 is false, NaN is true), and `&&` / `||` / `!` must give 0/1.
 * The right operand of `&&` / `||` went into the result raw (`0 || 0.5`
 * was 0, `0 || 1e-300` was 89) and `!f` was converted back from a float
 * (`!0.0` was 0, `!!0.5` was 0). */

I64 correct = 0, tests = 0;
I64 calls = 0;

U0 Check(I64 got, I64 want)
{
  tests++;
  if (got == want) correct++;
  else "\n  got %d want %d (test %d)", got, want, tests;
}

F64 Side(F64 v) { calls++; return v; }

F64 MakeNan() { F64 z = 0.0; return z / z; }

/* t: the C truth value of g (and of f, its F32 copy, given as tf). */
U0 Truth(F64 g, I64 t, I64 tf)
{
  I64 z = 0, o = 1, w = 0, c;
  F32 f = g;
  F64 h;

  c = z || g; Check(c, t);
  c = g || z; Check(c, t);
  c = o && g; Check(c, t);
  c = g && o; Check(c, t);
  c = g && g; Check(c, t);
  c = g || g; Check(c, t);
  c = z && g; Check(c, 0);
  c = o || g; Check(c, 1);
  c = !g; Check(c, !t);
  c = !!g; Check(c, t);
  c = (z || g) + (o && g) * 2; Check(c, t * 3);
  if (g) c = 1; else c = 0; Check(c, t);
  if (!g) c = 1; else c = 0; Check(c, !t);
  if (z || g) c = 1; else c = 0; Check(c, t);
  if (g && o) c = 1; else c = 0; Check(c, t);
  while (g) { w++; break; } Check(w, t);
  h = g;
  for (w = 0; h; w++) h = 0.0; Check(w, t);

  c = z || f; Check(c, tf);
  c = f || z; Check(c, tf);
  c = o && f; Check(c, tf);
  c = f && o; Check(c, tf);
  c = !f; Check(c, !tf);
  c = !!f; Check(c, tf);
  if (f) c = 1; else c = 0; Check(c, tf);
}

I32 Main()
{
  I64 c, z = 0, o = 1;

  "Test - float operands of && || ! and conditions: ";

  Truth(0.5, 1, 1);
  Truth(0.0, 0, 0);
  Truth(-0.0, 0, 0);
  Truth(MakeNan(), 1, 1);
  Truth(1e-300, 1, 0); /* (F32)1e-300 is 0.0 */
  Truth(256.0, 1, 1);
  Truth(-2.5, 1, 1);

  /* Literal operands. */
  c = 0 || 0.5; Check(c, 1);
  c = 0.25 && 1; Check(c, 1);
  c = !0.0; Check(c, 1);

  /* Short-circuit still skips the right operand. */
  c = z || Side(0.0); Check(c, 0);
  c = o || Side(1.0); Check(c, 1);
  c = z && Side(1.0); Check(c, 0);
  c = o && Side(0.5); Check(c, 1);
  Check(calls, 2);

  /* do-while on a float counts down to exactly 0.0. */
  F64 g = 0.5;
  c = 0;
  do { c++; g -= 0.25; } while (g);
  Check(c, 2);

  /* `!` on a pointer and an int is still 0/1. */
  U8 *p = NULL;
  c = !p; Check(c, 1);
  p = "x";
  c = !p; Check(c, 0);
  c = !256; Check(c, 0);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
