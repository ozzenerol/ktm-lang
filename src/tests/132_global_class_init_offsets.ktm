#include "testhelper.ktm"

/* A global class initialiser wrote its items back to back, ignoring the
 * field offsets: `P gp = {1,2,3,4}` with `I32 a; I64 b;` put `b` at
 * offset 4 instead of 8, so `gp.b` read garbage (and a string field
 * read as a pointer crashed). Each item must sit at its field's offset,
 * padded to the class size, in nested classes and arrays of classes
 * too. Locals were already right, so each global is compared with a
 * local initialised the same way. */

class P132
{
  I32 a;
  I64 b;
  U8 c;
  I16 d;
};

class Mix132
{
  U8 x;
  F64 f;
  U8 *s;
  F32 g;
  I16 h;
  U8 last;
};

class Nest132
{
  U8 tag;
  P132 p;
  I32 tail;
};

class Arr132
{
  U8 k;
  I32 v[3];
  I64 w;
};

class Deep132
{
  I16 id;
  P132 ps[2];
  Mix132 m;
  U8 end;
};

P132    g_p   = {1, 2, 3, 4};
U8      g_gap = 0x5A;
P132    g_pa[3] = {{5, 6, 7, 8}, {9, 10, 11, 12}, {-1, -2, 255, -4}};
Mix132  g_mix = {1, 2.5, "hi", 3.5, -7, 200};
Nest132 g_n   = {42, {1, 2, 3, 4}, 99};
Arr132  g_arr = {7, {10, 20, 30}, 40};
Deep132 g_d   = {3, {{1, 2, 3, 4}, {5, 6, 7, 8}},
                 {9, -1.25, "deep", 0.5, 11, 12}, 13};
/* Scalar arrays were laid out right before and must stay so. */
I32     g_i32[4] = {1, -2, 3, -4};
U8      g_u8[5]  = {1, 2, 3, 4, 5};
I16     g_i16[3] = {-1, 2, -3};
F32     g_f32[2] = {1.5, -2.25};
F64     g_f64[2] = {0.125, 8.5};
U8     *g_strs[2] = {"one", "two"};
I64     g_after = 0x1122334455667788;

Bool PEq(P132 *p, I64 a, I64 b, I64 c, I64 d)
{
  return p->a == a && p->b == b && p->c == c && p->d == d;
}

Bool MixEq(Mix132 *m, I64 x, F64 f, U8 *s, F64 g, I64 h, I64 last)
{
  return m->x == x && m->f == f && !StrCmp(m->s, s) && m->g == g &&
         m->h == h && m->last == last;
}

/* The same bytes as a local initialised the same way, padding aside. */
Bool SameP(P132 *a, P132 *b)
{
  return PEq(a, b->a, b->b, b->c, b->d);
}

I32 Main()
{
  "Test - global class initialiser field offsets: ";
  I64 correct = 0, tests = 24;

  P132 l_p = {1, 2, 3, 4};
  if (PEq(&g_p, 1, 2, 3, 4)) correct++;
  if (SameP(&g_p, &l_p)) correct++;
  if (g_gap == 0x5A) correct++;

  if (PEq(&g_pa[0], 5, 6, 7, 8)) correct++;
  if (PEq(&g_pa[1], 9, 10, 11, 12)) correct++;
  if (PEq(&g_pa[2], -1, -2, 255, -4)) correct++;
  /* Elements are sizeof apart. */
  if ((&g_pa[1])(U8 *) - (&g_pa[0])(U8 *) == sizeof(P132)) correct++;

  if (MixEq(&g_mix, 1, 2.5, "hi", 3.5, -7, 200)) correct++;

  if (g_n.tag == 42 && PEq(&g_n.p, 1, 2, 3, 4) && g_n.tail == 99) correct++;
  Nest132 l_n = {42, {1, 2, 3, 4}, 99};
  if (l_n.tail == g_n.tail && SameP(&l_n.p, &g_n.p)) correct++;

  if (g_arr.k == 7 && g_arr.v[0] == 10 && g_arr.v[1] == 20 &&
      g_arr.v[2] == 30 && g_arr.w == 40) correct++;

  if (g_d.id == 3 && PEq(&g_d.ps[0], 1, 2, 3, 4) &&
      PEq(&g_d.ps[1], 5, 6, 7, 8)) correct++;
  if (MixEq(&g_d.m, 9, -1.25, "deep", 0.5, 11, 12)) correct++;
  if (g_d.end == 13) correct++;

  if (g_i32[0] == 1 && g_i32[1] == -2 && g_i32[2] == 3 && g_i32[3] == -4)
    correct++;
  if (g_u8[0] == 1 && g_u8[2] == 3 && g_u8[4] == 5) correct++;
  if (g_i16[0] == -1 && g_i16[1] == 2 && g_i16[2] == -3) correct++;
  if (g_f32[0] == 1.5 && g_f32[1] == -2.25) correct++;
  if (g_f64[0] == 0.125 && g_f64[1] == 8.5) correct++;
  if (!StrCmp(g_strs[0], "one") && !StrCmp(g_strs[1], "two")) correct++;
  if (g_after == 0x1122334455667788) correct++;

  /* Function statics take the same path. */
  static P132 s_p = {21, 22, 23, 24};
  if (PEq(&s_p, 21, 22, 23, 24)) correct++;
  static Mix132 s_m[2] = {{1, 1.0, "a", 2.0, 3, 4}, {5, 6.0, "b", 7.0, 8, 9}};
  if (MixEq(&s_m[0], 1, 1.0, "a", 2.0, 3, 4)) correct++;
  if (MixEq(&s_m[1], 5, 6.0, "b", 7.0, 8, 9)) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
