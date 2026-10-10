#include "testhelper.ktm"

/* A prefix operator followed by `name[` took the whole rest of the
 * expression as its operand: `-a[0] == -6` parsed as `-(a[0] == -6)` and
 * `!a[i] && x` as `!(a[i] && x)`. Postfix operators ([], (), ., ->, ++, --,
 * a postfix cast) bind tighter than prefix ones and the result then takes
 * part in the binary expression, as in C. */

class Pt
{
  I64 x;
  I64 v[3];
  Pt *next;
};

I64 g_arr[3];

I64 *Arr(I64 k) { return g_arr + k; }
Pt *GetPt(Pt *p) { return p; }

I32 Main()
{
  I64 correct = 0, tests = 23;

  "Test - unary operator before a subscript: ";

  I64 a[2], m[2][2], i = 1, t;
  a[0] = 6; a[1] = 0;
  m[1][0] = 5;
  g_arr[0] = 3; g_arr[1] = 4; g_arr[2] = 12;
  I64 *p = a;
  /* U8 *: an `I64 *` array element reads back as its address (a
   * separate bug). */
  U8 bs[2], *pp[2];
  bs[0] = 6; bs[1] = 2;
  pp[0] = &bs[0]; pp[1] = &bs[1];
  Pt pt[2];
  pt[0].x = 7; pt[1].x = 9; pt[1].v[2] = 11;
  pt[0].next = &pt[1]; pt[1].next = NULL;
  Pt *pq = pt;
  F64 fa[2];
  fa[0] = 1.5;

  /* - + ~ ! before `[]`, then a binary operator. */
  if (-a[0] == -6) correct++;
  if (-a[0] * 2 == -12 && -a[0] + 10 == 4) correct++;
  if (+a[0] == 6 && +a[0] - 1 == 5) correct++;
  if ((~a[0] & 0xF) == 9 && ~a[0] == -7) correct++;
  if (!a[1] == 1 && !a[0] == 0) correct++;
  t = 0;
  if (!a[1] && a[0] == 6) t = 1;          /* was !(a[1] && a[0] == 6) */
  if (!a[0] || a[1]) t = 0;
  if (t == 1) correct++;
  if (-m[1][0] == -5 && -p[0] == -6 && -fa[0] == -1.5) correct++;

  /* * & ++ -- before `[]`. */
  if (*pp[0] == 6 && *pp[0] + 1 == 7) correct++;
  if (&a[1] == p + 1 && &a[0] + 1 == &a[1]) correct++;
  if (++a[1] == 1 && a[1] == 1) correct++;
  if (--a[1] + 5 == 5 && a[1] == 0) correct++;

  /* `.` and `->` after the subscript. */
  if (-pt[1].x == -9 && -pt[0].next->x * 2 == -18) correct++;
  if (-pq[1].v[2] == -11 && ~pq->v[0] == -1 - pq->v[0]) correct++;
  if (&pt[1].x == &pt[0].next->x) correct++;

  /* A call result subscripted / dereferenced. */
  if (-Arr(1)[0] == -4 && -Arr(0)[2] / 4 == -3) correct++;
  if (-GetPt(pt)->x == -7 && !GetPt(pt)[1].next == 1) correct++;

  /* Postfix ++/-- and a postfix cast inside the prefix operator. */
  a[1] = 2;
  if (-a[1]++ == -2 && a[1] == 3) correct++;
  if (-a[1]-- * 2 == -6 && a[1] == 2) correct++;
  if (-fa[0](I64) == -1 && -a[0](F64) == -6.0) correct++;

  /* A cast is not an l-value: `&x(T)` casts the address. */
  if (&a[1](U8 *) - a(U8 *) == 8 && &i(U8 *) == (&i)(U8 *) &&
      &pt[1].x(I64) == (&pt[1].x)(I64)) correct++;

  /* A parenthesised operand is postfix-expanded too. */
  if (-(p)[0] == -6 && *(pp)[1] == 2 && -(pt)[1].x == -9) correct++;

  /* sizeof binds to the subscripted element, not the array. */
  if (sizeof a[0] == 8 && sizeof pt[0].v == 24) correct++;

  /* Stacked prefix operators. */
  if (- -a[0] == 6 && -~a[0] == 7 && !!a[0] + 1 == 2) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
