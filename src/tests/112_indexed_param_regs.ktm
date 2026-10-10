#include "testhelper.ktm"

/* `p[i]` where both p and i arrive as parameters. On arm64 AOT the
 * pointer was reloaded into x1, which still held the index, so
 * `I64 Ai(I64 *p, I64 i) { return p[i]; }` loaded from p + p*8
 * (`ldr x0, [x1, x1, lsl #3]`) and crashed. Stores and `+=` also
 * read the index out of its arg register after other code had
 * overwritten it. */

I64 g_v[4] = {10, 20, 30, 40};

I64 LdI64(I64 *p, I64 i) { return p[i]; }
I64 LdIdxFirst(I64 i, I64 *p) { return p[i]; }
I64 LdX2(I64 a, I64 b, I64 *p, I64 i) { return p[i]; }
I64 LdSkip(I64 *p, I64 a, I64 i) { return p[i]; }
I64 LdX4(I64 a, I64 b, I64 c, I64 d, I64 *p, I64 i) { return p[i]; }
U8 LdU8(U8 *p, I64 i) { return p[i]; }
I32 LdI32(I32 *p, I64 i) { return p[i]; }
F64 LdF64(F64 *p, I64 i) { return p[i]; }
I32 LdDay(CDate *p, I64 i) { return p[i].date; }
U32 LdTime(CDate *p, I64 i) { return p[i].time; }
I64 LdSum(I64 *p, I64 i, I64 j) { return p[i + j]; }
I64 LdPlus1(I64 *p, I64 i) { return p[i + 1]; }
I64 LdNested(I64 *p, I64 i) { return LdI64(p, LdI64(p, i) - 18); }
I64 LdGlobal(I64 i) { return g_v[i]; }

U0 StI64(I64 *p, I64 i, I64 v) { p[i] = v; }
U0 StX2(I64 a, I64 b, I64 *p, I64 i, I64 v) { p[i] = v; }
U0 StI32(I32 *p, I64 i, I32 v) { p[i] = v; }
U0 StF64(F64 *p, I64 i, F64 v) { p[i] = v; }
U0 StPlus1(I64 *p, I64 i, I64 v) { p[i + 1] = v; }

U0 AddIdxFirst(I64 i, I64 *p) { p[i] += 5; }
U0 AddI64(I64 *p, I64 i) { p[i] += 7; }
U0 OrX2(I64 a, I64 b, I64 *p, I64 i) { p[i] |= 0x100; }
U0 AddMul(I64 *p, I64 i, I64 a, I64 b) { p[i] += a * b; }
U0 AddSum(I64 *p, I64 i, I64 j) { p[i + j] += 5; }
U0 IncU8(U8 *p, I64 i) { p[i]++; }

I32 Main()
{
  I64 correct = 0, tests = 26;

  "Test - indexed access with pointer and index parameters: ";

  I64 v[4] = {10, 20, 30, 40};
  U8 b[4] = {1, 2, 3, 4};
  I32 w[4] = {-1, -2, -3, -4};
  F64 f[4] = {0.5, 1.5, 2.5, 3.5};
  CDate d[3];
  d[0].date = 100; d[1].date = 200; d[2].date = 300;
  d[0].time = 1; d[1].time = 2; d[2].time = 3;

  /* Loads */
  if (LdI64(v, 2) == 30) correct++;
  if (LdIdxFirst(3, v) == 40) correct++;
  if (LdX2(0, 0, v, 3) == 40) correct++;
  if (LdSkip(v, 0, 1) == 20) correct++;
  if (LdX4(0, 0, 0, 0, v, 1) == 20) correct++;
  if (LdU8(b, 3) == 4) correct++;
  if (LdI32(w, 2) == -3) correct++;
  if (LdF64(f, 1) == 1.5) correct++;
  if (LdDay(d, 2) == 300 && LdTime(d, 1) == 2) correct++;
  if (LdSum(v, 1, 2) == 40) correct++;
  if (LdPlus1(v, 2) == 40) correct++;
  if (LdNested(v, 1) == 30) correct++;
  if (LdGlobal(2) == 30) correct++;

  /* Stores */
  StI64(v, 0, 11);
  if (v[0] == 11 && v[1] == 20) correct++;
  StX2(0, 0, v, 1, 21);
  if (v[1] == 21 && v[0] == 11) correct++;
  StI32(w, 1, 77);
  if (w[1] == 77 && w[0] == -1 && w[2] == -3) correct++;
  StF64(f, 2, 9.5);
  if (f[2] == 9.5 && f[1] == 1.5) correct++;
  StPlus1(v, 2, 99);
  if (v[3] == 99 && v[2] == 30) correct++;

  /* Read-modify-write */
  v[0] = 10; v[1] = 20; v[2] = 30; v[3] = 40;
  AddIdxFirst(2, v);
  if (v[2] == 35 && v[0] == 10) correct++;
  AddI64(v, 3);
  if (v[3] == 47 && v[1] == 20) correct++;
  OrX2(0, 0, v, 0);
  if (v[0] == 0x10A) correct++;
  AddMul(v, 1, 3, 4);
  if (v[1] == 32) correct++;
  AddSum(v, 1, 1);
  if (v[2] == 40) correct++;
  IncU8(b, 1);
  if (b[1] == 3 && b[0] == 1 && b[2] == 3) correct++;
  /* Nothing else was written through a wild pointer. */
  if (v[0] == 0x10A && v[1] == 32 && v[2] == 40 && v[3] == 47) correct++;
  if (b[0] == 1 && b[3] == 4 && w[3] == -4 && f[3] == 3.5) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
