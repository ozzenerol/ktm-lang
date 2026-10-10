#include "testhelper.ktm"

/* A brace initialiser with fewer items than the object has elements or
 * fields must zero the rest, as in C. A global `P gp = {1};` only
 * emitted the first item, so the next global's bytes showed up in
 * gp.b, and a local `P lp = {9};` left the other fields as stack
 * garbage. Nested lists `{{1},{2}}` also packed their items together
 * instead of starting each at its own element. */

class P133
{
  I64 a, b, c;
};

class Arr133
{
  I64 v[4];
  I64 w;
};

class Nest133
{
  I64 t;
  P133 p;
  I64 u;
};

P133    g_p = {1};
I64     g_next = -1;
I64     g_arr[4] = {1, 2};
I64     g_next2 = -1;
I64     g_m[3][3] = {{1}, {2, 3}};
P133    g_ps[3] = {{1}, {2, 3}};
Arr133  g_a = {{1, 2}, 9};
Nest133 g_n = {1, {2}};
F64     g_f[3] = {1.5};
I32     g_i[5] = {7};
U8      g_b[6] = {1, 2};
I64     g_full[3] = {4, 5, 6};
I64     g_last = -1;

Bool PIs(P133 *p, I64 a, I64 b, I64 c)
{
  return p->a == a && p->b == b && p->c == c;
}

I64 Dirty()
{
  I64 junk[512], i, sum = 0;
  for (i = 0; i < 512; i++) junk[i] = -1 - i;
  for (i = 0; i < 512; i++) sum += junk[i];
  return sum;
}

I64 Locals()
{
  I64 correct = 0, i;

  P133 p = {9};
  if (PIs(&p, 9, 0, 0)) correct++;

  I64 arr[4] = {1, 2};
  if (arr[0] == 1 && arr[1] == 2 && arr[2] == 0 && arr[3] == 0) correct++;

  I64 m[3][3] = {{1}, {2, 3}};
  if (m[0][0] == 1 && m[0][1] == 0 && m[0][2] == 0 && m[1][0] == 2 &&
      m[1][1] == 3 && m[1][2] == 0 && m[2][0] == 0 && m[2][2] == 0)
    correct++;

  P133 ps[3] = {{1}, {2, 3}};
  if (PIs(&ps[0], 1, 0, 0) && PIs(&ps[1], 2, 3, 0) && PIs(&ps[2], 0, 0, 0))
    correct++;

  Arr133 a = {{1, 2}, 9};
  if (a.v[0] == 1 && a.v[1] == 2 && a.v[2] == 0 && a.v[3] == 0 && a.w == 9)
    correct++;

  Nest133 n = {1, {2}};
  if (n.t == 1 && PIs(&n.p, 2, 0, 0) && n.u == 0) correct++;

  F64 f[3] = {1.5};
  I32 v_i32[5] = {7};
  U8 b[6] = {1, 2};
  if (f[0] == 1.5 && f[1] == 0.0 && f[2] == 0.0) correct++;
  if (v_i32[0] == 7 && v_i32[1] == 0 && v_i32[4] == 0) correct++;
  if (b[0] == 1 && b[1] == 2 && b[2] == 0 && b[5] == 0) correct++;

  /* Bigger than the inline-store limit: cleared with memset. */
  I64 big[40] = {1, 2};
  Bool big_ok = big[0] == 1 && big[1] == 2;
  for (i = 2; i < 40; i++) {
    if (big[i]) big_ok = FALSE;
  }
  if (big_ok) correct++;

  /* Fully initialised objects are unaffected. */
  I64 full[3] = {4, 5, 6};
  P133 pf = {7, 8, 9};
  if (full[0] == 4 && full[2] == 6 && PIs(&pf, 7, 8, 9)) correct++;

  /* Re-initialised on every pass of a loop. */
  Bool loop_ok = TRUE;
  for (i = 0; i < 3; i++) {
    P133 lp = {i};
    if (!PIs(&lp, i, 0, 0)) loop_ok = FALSE;
    lp.b = 99;
    lp.c = 99;
  }
  if (loop_ok) correct++;
  return correct;
}

I32 Main()
{
  "Test - initialiser zero fill: ";
  I64 correct = 0, tests = 24;

  if (PIs(&g_p, 1, 0, 0)) correct++;
  if (g_next == -1 && g_next2 == -1 && g_last == -1) correct++;
  if (g_arr[0] == 1 && g_arr[1] == 2 && g_arr[2] == 0 && g_arr[3] == 0)
    correct++;
  if (g_m[0][0] == 1 && g_m[0][1] == 0 && g_m[0][2] == 0 &&
      g_m[1][0] == 2 && g_m[1][1] == 3 && g_m[1][2] == 0 &&
      g_m[2][0] == 0 && g_m[2][2] == 0)
    correct++;
  if (PIs(&g_ps[0], 1, 0, 0) && PIs(&g_ps[1], 2, 3, 0) &&
      PIs(&g_ps[2], 0, 0, 0))
    correct++;
  if (g_a.v[0] == 1 && g_a.v[1] == 2 && g_a.v[2] == 0 && g_a.v[3] == 0 &&
      g_a.w == 9)
    correct++;
  if (g_n.t == 1 && PIs(&g_n.p, 2, 0, 0) && g_n.u == 0) correct++;
  if (g_f[0] == 1.5 && g_f[1] == 0.0 && g_f[2] == 0.0) correct++;
  if (g_i[0] == 7 && g_i[1] == 0 && g_i[4] == 0) correct++;
  if (g_b[0] == 1 && g_b[1] == 2 && g_b[2] == 0 && g_b[5] == 0) correct++;
  if (g_full[0] == 4 && g_full[1] == 5 && g_full[2] == 6) correct++;

  /* Leave non-zero values where Locals' frame will be. */
  if (Dirty() != 0) correct++;
  correct += Locals();

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
