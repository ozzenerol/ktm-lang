"Test - typeof: ";
#include "testhelper.ktm"

class TypeofVec2
{
  F64 x;
  F64 y;
};

I64 correct = 0;
I64 tests = 8;

F64 Half(F64 v) { return v / 2.0; }

U0 Main()
{
  I64 i = 5;
  U8 *s = "hi";
  TypeofVec2 v;

  if (!StrCmp(typeof(1+1), "i64")) correct++;
  if (!StrCmp(typeof(1.5), "f64")) correct++;
  if (!StrCmp(typeof(i), "i64")) correct++;
  if (!StrCmp(typeof(&i), "i64 *")) correct++;
  if (!StrCmp(typeof(s), "u8 *")) correct++;
  if (!StrCmp(typeof(v), "TypeofVec2")) correct++;
  if (!StrCmp(typeof(U8*), "u8 *")) correct++;
  /* The operand is type-checked, not evaluated. */
  if (!StrCmp(typeof(Half(4.0)), "f64")) correct++;
}
Main;

PrintResult(correct, tests);
'===\n';
Exit(0);
