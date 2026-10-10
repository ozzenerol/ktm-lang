#include "testhelper.ktm"

/* Calling a ktm `...` function through a function pointer passed the
 * implicit argc and the extra arguments in registers, while the callee
 * reads them from the stack: `sp("a", 1, 2, 3)` summed garbage on
 * x86_64 and arm64 (or crashed). The backends only recognised a
 * variadic callee by looking up the name of a direct call. */

I64 Sum(U8 *t, ...)
{
  I64 i, s = 0;
  for (i = 0; i < argc; i++) s += argv[i];
  return s;
}

I64 Count(U8 *t, ...)
{
  return argc;
}

/* Named args mixed with extras; weights the extras by position. */
I64 Mix(I64 a, I64 b, ...)
{
  I64 i, s = a * 100 + b * 10;
  for (i = 0; i < argc; i++) s += argv[i] * (i + 1);
  return s + argc * 100000;
}

F64 Scale(F64 k, ...)
{
  I64 i, s = 0;
  for (i = 0; i < argc; i++) s += argv[i];
  return k * s;
}

U8 *Format(U8 *fmt, ...)
{
  if (argc == 0) return StrNew(fmt);
  return StrPrintJoin(NULL, fmt, argc, argv);
}

class H
{
  I64 (*count)(U8 *t, ...);
  I64 (*sum)(U8 *t, ...);
  I64 v;
};

H g_h;

/* A function pointer parameter */
I64 Via(I64 (*f)(U8 *t, ...))
{
  return f("v", 10, 20, 30, 40, 50, 60, 70, 80);
}

/* Leaves non-zero values where a following call's argc would go */
I64 Junk(U8 *a, I64 b, I64 c, I64 d)
{
  return b + c + d;
}

I32 Main()
{
  "Test - call a variadic function through a function pointer: ";
  I64 tests = 17, correct = 0;
  U8 *s;

  I64 (*sp)(U8 *t, ...) = &Sum;
  I64 (*cp)(U8 *t, ...) = &Count;
  I64 (*mp)(I64 a, I64 b, ...) = &Mix;
  F64 (*kp)(F64 k, ...) = &Scale;
  U8 *(*fp)(U8 *fmt, ...) = &Format;

  if (sp("a", 1, 2, 3) == 6) correct++;
  else "Failed - local pointer sum\n";
  Junk("x", 77, 88, 99);
  if (cp("a") == 0) correct++;
  else "Failed - local pointer, no extra arguments\n";
  Junk("x", 77, 88, 99);
  if (sp("a") == 0) correct++;
  else "Failed - local pointer sum, no extra arguments\n";
  if (sp("a", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10) == 55) correct++;
  else "Failed - local pointer, many extra arguments\n";
  if (mp(1, 2, 3, 4, 5, 6, 7, 8) == 600253) correct++;
  else "Failed - named arguments mixed with extras\n";
  if (mp(1, 2) == 120) correct++;
  else "Failed - named arguments, no extras\n";
  if (mp(1, 2) + sp("x", 7) * 2 == 134) correct++;
  else "Failed - results used in an expression\n";
  if (kp(0.5, 4, 6) == 5.0) correct++;
  else "Failed - F64 named argument with extras\n";
  if (Via(&Sum) == 360) correct++;
  else "Failed - function pointer parameter\n";

  H h;
  h.count = &Count;
  h.sum = &Sum;
  H *p = &h;
  H *arr[2];
  arr[0] = &h;
  arr[1] = &h;
  g_h.count = &Count;
  g_h.sum = &Sum;

  if (h.count("a", 1, 2) == 2 && h.sum("a", 1, 2) == 3) correct++;
  else "Failed - class field\n";
  Junk("x", 77, 88, 99);
  if (p->count("a") == 0) correct++;
  else "Failed - field through a pointer, no extra arguments\n";
  if (p->sum("a", 5, 6, 7) == 18) correct++;
  else "Failed - field through a pointer\n";
  if (arr[1]->count("a", 1, 2, 3, 4) == 4 &&
      arr[0]->sum("a", 1, 2, 3, 4, 5, 6, 7, 8, 9) == 45) correct++;
  else "Failed - field through an array element\n";
  Junk("x", 77, 88, 99);
  if (arr[1]->count("a") == 0) correct++;
  else "Failed - field through an array element, no extra arguments\n";
  if (g_h.sum("a", 4, 5) == 9 && g_h.count("a") == 0) correct++;
  else "Failed - global class field\n";

  s = fp("keep %s and 50%%");
  if (!StrCmp(s, "keep %s and 50%%")) correct++;
  else "Failed - format through a pointer, no arguments: '%s'\n", s;
  Free(s);
  s = fp("%s=%d", "x", 42);
  if (!StrCmp(s, "x=42")) correct++;
  else "Failed - format through a pointer: '%s'\n", s;
  Free(s);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
