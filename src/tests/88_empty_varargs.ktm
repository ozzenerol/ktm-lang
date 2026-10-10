#include "testhelper.ktm"

/* A ktm `...` function always gets the implicit argc/argv, but a call
 * that passed no extra arguments (`Count("a")`) didn't pass argc at all,
 * so the callee read whatever was left in that register or stack slot:
 * Shlurp("printf '%%s' ''") formatted with a garbage argc and argv. */

I64 Count(U8 *fmt, ...)
{
  return argc;
}

I64 Sum(U8 *fmt, ...)
{
  I64 i, total = 0;
  for (i = 0; i < argc; i++) total += argv[i];
  return total;
}

/* argc comes after the default arguments that were filled in */
I64 Def(I64 a, I64 b = 10, ...)
{
  return a + b + argc * 1000;
}

I64 AllDefault(I64 a = 1, ...)
{
  return a + argc * 1000;
}

/* As Shlurp and Sh do: use fmt as-is when there is nothing to format */
U8 *Format(U8 *fmt, ...)
{
  if (argc == 0) return StrNew(fmt);
  return StrPrintJoin(NULL, fmt, argc, argv);
}

/* Leaves a non-zero value where a following call's argc would go */
I64 Junk(U8 *a, I64 b, I64 c, I64 d)
{
  return b + c + d;
}

I32 Main()
{
  "Test - calls with no variadic arguments: ";
  I64 tests = 11, correct = 0;
  U8 *s;

  Junk("x", 77, 88, 99);
  if (Count("a") == 0) correct++;
  else "Failed - argc with no arguments\n";
  if (Count("a", 5) == 1) correct++;
  else "Failed - argc with one argument\n";
  if (Count("a", 5, 6, 7) == 3) correct++;
  else "Failed - argc with three arguments\n";

  Junk("x", 77, 88, 99);
  if (Sum("a") == 0) correct++;
  else "Failed - argv loop with no arguments\n";
  if (Sum("a", 1, 2, 3) == 6) correct++;
  else "Failed - argv loop with arguments\n";

  if (Def(1) == 11) correct++;
  else "Failed - default argument, no variadic arguments\n";
  if (Def(1, 2) == 3) correct++;
  else "Failed - explicit argument, no variadic arguments\n";
  if (Def(1, 2, 3) == 1003) correct++;
  else "Failed - default argument with a variadic argument\n";
  if (AllDefault() == 1 && AllDefault(5, 6) == 1005) correct++;
  else "Failed - all-default variadic function\n";

  Junk("x", 77, 88, 99);
  s = Format("keep %s and 50%%");
  if (!StrCmp(s, "keep %s and 50%%")) correct++;
  else "Failed - format string used as-is: '%s'\n", s;
  Free(s);
  s = Format("got %s", "it");
  if (!StrCmp(s, "got it")) correct++;
  else "Failed - format string with an argument: '%s'\n", s;
  Free(s);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
