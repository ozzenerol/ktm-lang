#include "testhelper.ktm"

/* A global array or class initialiser is written out as static data, which
 * only held literals and strings: any other item - `&gx`, `&Foo`, `gx`,
 * `&arr[1]` - was silently left as 0. Such items are now stored at startup,
 * like a scalar global's `I64 g = gx;`. */

class Pt
{
  I64 x;
  I64 y;
};

class Fn
{
  I64 (*f)(I64 v);
  I64 k;
};

class Mixed
{
  U8 tag;
  I32 n;
  F64 d;
};

I64 Dbl(I64 v) { return v * 2; }
I64 Inc(I64 v) { return v + 1; }

I64 gx = 5;
I64 gy = 9;
F64 gf = 2.5;
I64 garr[3] = {10, 20, 30};
Pt gp = {7, 8};

I64 *arrp[4] = {&gx, &garr[1], &gp.y, NULL};
I64 nonc[3] = {gx, 2, gx + gy};
I64 mix[4] = {1, gx};
U8 bytes[4] = {1, gx, 3};
F64 floats[2] = {gf, 1.5};
U0 *fns[2] = {&Dbl, &Inc};
U8 *strs[2] = {"ab", "cd"};
Pt gq = {gy, 3};
Pt pts[2] = {{1, gx}, {gy, 4}};
Fn gfn = {&Dbl, 4};
Fn ftab[2] = {{&Dbl, 1}, {&Inc, 2}};
Mixed gm = {7, gx, gf};
I64 g2 = gx * 2;
I64 dep[2] = {g2, g2 + 1};

I64 StaticGet(I64 i)
{
  static I64 *tab[2] = {&gx, &gy};
  I64 *raw = tab(I64 *);
  return raw[i];
}

I32 Main()
{
  I64 correct = 0, tests = 30;

  "Test - non-constant items in a global initialiser: ";

  /* Read the pointer slots as I64s. */
  I64 *raw = arrp(I64 *);
  if (raw[0] == (&gx)(I64)) correct++;
  if (raw[1] == (&garr[1])(I64)) correct++;
  if (raw[2] == (&gp.y)(I64)) correct++;
  if (raw[3] == 0) correct++;

  if (nonc[0] == 5) correct++;
  if (nonc[1] == 2) correct++;
  if (nonc[2] == 14) correct++;

  if (mix[0] == 1 && mix[1] == 5) correct++;
  if (mix[2] == 0 && mix[3] == 0) correct++;

  if (bytes[0] == 1 && bytes[1] == 5) correct++;
  if (bytes[2] == 3 && bytes[3] == 0) correct++;

  if (floats[0] == 2.5) correct++;
  if (floats[1] == 1.5) correct++;

  if (fns[0] == (&Dbl)(U0 *)) correct++;
  if (ftab[0].f(4) == 8 && ftab[1].f(4) == 5) correct++;

  if (!StrCmp(strs[0], "ab") && !StrCmp(strs[1], "cd")) correct++;

  if (gq.x == 9 && gq.y == 3) correct++;
  if (pts[0].x == 1 && pts[0].y == 5) correct++;
  if (pts[1].x == 9 && pts[1].y == 4) correct++;

  if (gfn.f(21) == 42) correct++;
  if (gfn.k == 4) correct++;

  if (gm.tag == 7) correct++;
  if (gm.n == 5) correct++;
  if (gm.d == 2.5) correct++;

  /* Run in program order, after the globals they read. */
  if (g2 == 10) correct++;
  if (dep[0] == 10 && dep[1] == 11) correct++;

  if (StaticGet(0) == (&gx)(I64)) correct++;
  if (StaticGet(1) == (&gy)(I64)) correct++;

  /* The stored pointers are live. */
  gx = 6;
  I64 *p = raw[0](I64 *);
  if (*p == 6) correct++;
  p = raw[1](I64 *);
  if (*p == 20) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
