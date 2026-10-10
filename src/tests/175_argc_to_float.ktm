#include "testhelper.ktm"

/* argc and the argv slots shared one type tagged as a variadic slot,
 * so converting argc to a float reinterpreted its bits like
 * `argv[i](F64)` does, instead of converting the count: 0.0. */

F64 RetArgc(...) { return argc; }
F64 AssignArgc(...) { F64 f = argc; return f; }
F64 MulArgc(...) { return argc * 1.5; }
F64 CastArgc(...) { return argc(F64); }
F64 Half(F64 x) { return x / 2.0; }
F64 PassArgc(...) { return Half(argc); }
Bool CmpArgc(...) { F64 two = 2.0; return argc == two; }
F64 SumF64(...)
{
  F64 sum = 0.0;
  I64 i;
  for (i = 0; i < argc; i++) {
    sum += argv[i](F64);
  }
  return sum;
}
I64 IntArgc(...) { return argc; }

I32 Main()
{
  I64 correct = 0, tests = 10;

  "Test - argc converted to a float: ";

  if (RetArgc(1, 2) == 2.0) correct++;
  if (RetArgc() == 0.0) correct++;
  if (AssignArgc(1, 2, 3) == 3.0) correct++;
  if (MulArgc(7, 8) == 3.0) correct++;
  if (CastArgc(1, 2, 3, 4) == 4.0) correct++;
  if (PassArgc(1, 2, 3, 4, 5, 6) == 3.0) correct++;
  if (CmpArgc(9, 9)) correct++;
  /* the argv slots still hold the F64 bits */
  if (SumF64(1.5, 2.25) == 3.75) correct++;
  if (IntArgc(1, 2, 3) == 3) correct++;
  if (RetArgc(1, 2, 3, 4, 5, 6, 7, 8, 9, 10) == 10.0) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
