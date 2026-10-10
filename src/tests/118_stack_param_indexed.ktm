#include "testhelper.ktm"

/* Parameters past the argument registers (the 7th integer argument on
 * x86_64, the 9th on arm64, the 9th float on x86_64) arrive on the
 * stack. When such a param was read only as the index of `p[i]` or as
 * a returned value, it got no stack slot and the compiler panicked
 * with `ir-regalloc: no slot for var.id=.. kind=5`:
 * `I64 X(I64 a, ..., I64 f, I64 *p, I64 i) { return p[i]; }`. */

/* x86_64: p and i on the stack */
I64 Ld8(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 *p, I64 i)
{ return p[i]; }
I64 LdCopy8(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 *p, I64 i)
{ I64 *q = p; return q[i]; }
/* Register pointer, stack index */
I64 LdMix7(I64 *p, I64 b, I64 c, I64 d, I64 e, I64 f, I64 i)
{ return p[i]; }
/* Stack pointer, register index */
I64 LdMixR7(I64 i, I64 b, I64 c, I64 d, I64 e, I64 f, I64 *p)
{ return p[i]; }
/* Plain use of a stack param */
I64 Ret7(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 i) { return i; }
I64 Ret10(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, I64 h,
          I64 j, I64 k) { return k; }
I64 Sum10(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, I64 h,
          I64 j, I64 k) { return a + b + c + d + e + f + g + h + j + k; }
/* arm64: p and i on the stack (9th and 10th) */
I64 Ld10(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, I64 h,
         I64 *p, I64 i) { return p[i]; }
I64 LdCopy10(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, I64 h,
             I64 *p, I64 i) { I64 *q = p; return q[i]; }
/* Stack param reassigned before use */
I64 Bump9(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, I64 h,
          I64 i) { i = i + 1; return i; }
U8 LdU8_10(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, I64 h,
           U8 *p, I64 i) { return p[i]; }
F64 LdF64_10(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, I64 h,
             F64 *p, I64 i) { return p[i]; }

/* Stores and compound assignment */
U0 St8(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 *p, I64 i)
{ p[i] = 77; }
U0 StV10(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, I64 h,
         I64 *p, I64 i, I64 v) { p[i] = v; }
U0 Add10(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, I64 h,
         I64 *p, I64 i) { p[i] += 5; }
U0 StF64_10(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, I64 h,
            F64 *p, I64 i, F64 v) { p[i] = v; }
U0 AddF64_9(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, I64 h,
            F64 *p, I64 i) { p[i] += 1.0; }

/* Float params past the 8 float registers */
F64 RetF9(F64 a, F64 b, F64 c, F64 d, F64 e, F64 f, F64 g, F64 h, F64 k)
{ return k; }

I32 Main()
{
  I64 correct = 0, tests = 22;

  "Test - indexed access with stack-passed parameters: ";

  I64 v[4] = {10, 20, 30, 40};
  U8 b[4] = {1, 2, 3, 4};
  F64 fv[4] = {0.5, 1.5, 2.5, 3.5};

  /* Loads */
  if (Ld8(0, 0, 0, 0, 0, 0, v, 2) == 30) correct++;
  if (LdCopy8(0, 0, 0, 0, 0, 0, v, 3) == 40) correct++;
  if (LdMix7(v, 0, 0, 0, 0, 0, 1) == 20) correct++;
  if (LdMixR7(2, 0, 0, 0, 0, 0, v) == 30) correct++;
  if (Ld10(0, 0, 0, 0, 0, 0, 0, 0, v, 1) == 20) correct++;
  if (LdCopy10(0, 0, 0, 0, 0, 0, 0, 0, v, 0) == 10) correct++;
  if (LdU8_10(0, 0, 0, 0, 0, 0, 0, 0, b, 3) == 4) correct++;
  if (LdF64_10(0, 0, 0, 0, 0, 0, 0, 0, fv, 2) == 2.5) correct++;

  /* Plain stack params */
  if (Ret7(0, 0, 0, 0, 0, 0, 5) == 5) correct++;
  if (Ret10(0, 0, 0, 0, 0, 0, 0, 0, 0, 9) == 9) correct++;
  if (Sum10(1, 2, 3, 4, 5, 6, 7, 8, 9, 10) == 55) correct++;
  if (Bump9(0, 0, 0, 0, 0, 0, 0, 0, 41) == 42) correct++;
  if (RetF9(0, 0, 0, 0, 0, 0, 0, 0, 1.25) == 1.25) correct++;

  /* Stores, +=, F64 elements */
  St8(0, 0, 0, 0, 0, 0, v, 0);
  if (v[0] == 77) correct++;
  StV10(0, 0, 0, 0, 0, 0, 0, 0, v, 1, -3);
  if (v[1] == -3) correct++;
  Add10(0, 0, 0, 0, 0, 0, 0, 0, v, 2);
  if (v[2] == 35) correct++;
  if (v[3] == 40) correct++;
  StF64_10(0, 0, 0, 0, 0, 0, 0, 0, fv, 0, 9.5);
  if (fv[0] == 9.5) correct++;
  AddF64_9(0, 0, 0, 0, 0, 0, 0, 0, fv, 3);
  if (fv[3] == 4.5) correct++;
  if (fv[1] == 1.5) correct++;

  /* Through a function pointer */
  I64 (*fp)(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, I64 h,
            I64 *p, I64 i) = &Ld10;
  if (fp(0, 0, 0, 0, 0, 0, 0, 0, v, 3) == 40) correct++;
  if (Ld8(0, 0, 0, 0, 0, 0, v, 1) == -3) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
