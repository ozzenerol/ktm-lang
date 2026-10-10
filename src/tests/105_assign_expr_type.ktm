#include "testhelper.ktm"

/* An assignment used as a value has the type of its left-hand side (as in
 * C). It used to be typed I64 for an I64-sized class, so `(c = Mk()).date`
 * failed with "Expected as class", and a by-value class assignment could not
 * have a field read from it either. */

class Big
{
  I64 a, b, c;
};

class Pt
{
  I64 x, y;
};

CDate g_date;
I64 g_idx = 0;

CDate MkDate(I32 date, U32 time)
{
  CDate d;
  d.date = date;
  d.time = time;
  return d;
}

Big MkBig()
{
  Big b;
  b.a = 1;
  b.b = 2;
  b.c = 3;
  return b;
}

I64 Next()
{
  g_idx++;
  if (g_idx > 3) return 0;
  return g_idx;
}

I32 Main()
{
  I64 correct = 0, tests = 18;

  "Test - assignment used as a value: ";

  /* I64-sized (intrinsic) class */
  CDate c;
  if ((c = MkDate(7, 9)).date == 7) correct++;
  if (c.date == 7 && c.time == 9) correct++;
  if ((c = MkDate(-3, 11)).time == 11 && c.date == -3) correct++;
  if ((g_date = MkDate(5, 6)).date + g_date.time == 11) correct++;
  CDate *pc = MAlloc(sizeof(CDate));
  if ((*pc = MkDate(1, 2)).time == 2 && pc->date == 1) correct++;
  Free(pc);
  CDate d1, d2;
  d1 = d2 = MkDate(4, 8);
  if (d1.date == 4 && d2.time == 8) correct++;

  /* Larger by-value class */
  Big x, y;
  y.a = 4;
  y.b = 5;
  y.c = 6;
  if ((x = MkBig()).b == 2 && x.c == 3) correct++;
  if ((x = y).c == 6 && x.a == 4) correct++;

  /* Scalars */
  I64 a, b, i3;
  a = b = i3 = 5;
  if (a == 5 && b == 5 && i3 == 5) correct++;
  F64 f;
  F64 g = (f = 1.5) * 2;
  if (g == 3.0) correct++;
  F32 f32;
  if ((f32 = 1.5) * 2 == 3.0) correct++;
  I64 i;
  F64 h = (i = 2.7) * 1.5;
  if (h == 3.0 && i == 2) correct++;
  I64 n = 3;
  if ((n += 2) * 10 == 50 && n == 5) correct++;

  /* Pointers and fields */
  Pt pt, *pp = &pt, *q;
  pt.x = 1;
  if ((pp->x += 2) == 3) correct++;
  if ((pt.y = 40) + 2 == 42) correct++;
  if ((q = pp)->x == 3 && q == pp) correct++;

  /* Conditions */
  I64 v, sum = 0;
  while ((v = Next())) sum += v;
  if (sum == 6) correct++;
  U8 *s = "abc", *t;
  I64 len = 0;
  while (*(t = s + len)) len++;
  if (len == 3) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
