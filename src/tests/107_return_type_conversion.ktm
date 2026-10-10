#include "testhelper.ktm"

/* A return value must be converted to the function's return type like an
 * assignment is: `F64 Conv(I64 u) { return u; }` returned the integer's
 * raw bits in the float register (Conv(3) printed 0.000000), and an
 * integer function returning a float expression returned garbage instead
 * of the truncated value. */

F64 Conv(I64 u) { return u; }
F64 Conv2(I64 u) { return u * 2; }
F64 Lit() { return 3; }
F64 NegLit() { return -4; }
F64 FromU8(U8 b) { return b; }
F64 FromI32(I32 b) { return b; }
F64 FromU32(U32 b) { return b; }
F32 F32Conv(I64 u) { return u; }
F64 Same(F64 f) { return f * 2.0; }
I64 Trunc(F64 f) { return f; }
I64 TruncExpr(F64 f) { return f * 2.0; }
I64 TruncF32(F32 f) { return f; }
U8 ToU8(F64 f) { return f; }
I32 ToI32(F64 f) { return f; }
U32 ToU32(F64 f) { return f; }
I64 Mixed(I64 u, F64 f) { if (u > 0) return f; return u; }

I32 Main()
{
  I64 correct = 0, tests = 20;
  I64 assign;
  F64 neg = -3.7;

  "Test - return value converted to the return type: ";

  if (Conv(3) == 3.0) correct++;
  if (Conv2(3) == 6.0) correct++;
  if (Lit() == 3.0) correct++;
  if (NegLit() == -4.0) correct++;
  if (FromU8(250) == 250.0 && FromI32(-7) == -7.0) correct++;
  if (FromU32(4000000000) == 4000000000.0) correct++;
  if (F32Conv(5) == 5.0) correct++;
  if (Same(1.25) == 2.5) correct++;

  /* Same truncation an assignment `I64 x = f;` does. */
  assign = neg;
  if (Trunc(3.7) == 3) correct++;
  if (Trunc(neg) == assign && assign == -3) correct++;
  if (TruncExpr(1.75) == 3) correct++;
  if (TruncF32(9.5) == 9) correct++;
  if (ToU8(200.5) == 200) correct++;
  if (ToI32(-5.5) == -5) correct++;
  if (ToU32(4000000000.0) == 4000000000) correct++;
  if (Mixed(1, 2.9) == 2 && Mixed(-6, 2.9) == -6) correct++;

  /* Through function pointers and nested calls. */
  F64 (*fp)(I64) = &Conv;
  I64 (*tp)(F64) = &Trunc;
  if (fp(9) == 9.0) correct++;
  if (tp(8.9) == 8) correct++;
  if (Conv(Trunc(Conv2(2))) == 4.0) correct++;
  if (Conv(Conv2(1)) == 2.0 && Trunc(Conv(Trunc(Lit()))) == 3) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
