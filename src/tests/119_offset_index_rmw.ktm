#include "testhelper.ktm"

/* `(p + k)[i]` and `p[i].field` address memory as base + index*scale
 * + a constant offset. The arm64 backends folded the offset into the
 * base register in place, so `(p + 1)[i] += 1` added it twice (the
 * store landed one element further) and a pointer parameter living
 * in that register was left pointing past where the caller put it. */

class Pt
{
  I64 x;
  F64 y;
  I32 z;
  I32 w;
};

U0 AddOff(I64 a, I64 b, I64 *p, I64 i) { (p + 1)[i] += 1; }
U0 AddOffFirst(I64 *p, I64 i) { (p + 1)[i] += 5; }
U0 SubNeg(I64 *p, I64 i) { (p - 1)[i] -= 3; }
U0 AddBig(I64 *p, I64 i) { (p + 1000)[i] += 9; }
U0 IncU8Off(U8 *p, I64 i) { (p + 2)[i]++; }
U0 OrI16Off(I16 *p, I64 i) { (p + 1)[i] |= 0x100; }
U0 AddI32Off(I32 *p, I64 i) { (p + 1)[i] += 10; }
U0 AddField(Pt *p, I64 i) { p[i].z += 4; }

I64 LdTwice(I64 *p, I64 i) { return (p + 1)[i] + (p + 1)[i]; }
I64 LdThenP(I64 *p, I64 i) { I64 r = (p + 1)[i]; return r + p[0]; }
I64 StThenP(I64 *p, I64 i, I64 v) { (p + 1)[i] = v; return p[0]; }
I64 StTwice(I64 *p, I64 i) { (p + 1)[i] = 7; (p + 1)[i + 1] = 8; return p[0]; }
I64 AddThenP(I64 *p, I64 i) { (p + 1)[i] += 1; return p[0] + p[i]; }
I64 AddTwice(I64 *p, I64 i) { (p + 1)[i] += 1; (p + 1)[i] += 1; return p[1]; }
I32 FieldTwice(Pt *p, I64 i) { p[i].z = p[i].w + p[i].z; return p[0].w; }

F64 LdF64Off(F64 *p, I64 i) { return (p + 1)[i]; }
U0 StF64Off(F64 *p, I64 i, F64 v) { (p + 1)[i] = v; }
F64 LdField(Pt *p, I64 i) { return p[i].y; }
U0 StField(Pt *p, I64 i, F64 v) { p[i].y = v; }

I32 Main()
{
  I64 correct = 0, tests = 22;

  "Test - offset base plus index read-modify-write: ";

  I64 v[4] = {10, 20, 30, 40};
  I64 big[1004];
  U8 b[6] = {1, 2, 3, 4, 5, 6};
  I16 h[4] = {1, 2, 3, 4};
  I32 w[4] = {-1, -2, -3, -4};
  F64 f[4] = {0.5, 1.5, 2.5, 3.5};
  Pt pts[3];
  I64 k;
  for (k = 0; k < 1004; k++) big[k] = k;
  for (k = 0; k < 3; k++) {
    pts[k].x = k; pts[k].y = k + 0.25; pts[k].z = k * 10; pts[k].w = k * 100;
  }

  /* Read-modify-write */
  AddOff(0, 0, v, 1);
  if (v[2] == 31 && v[3] == 40 && v[1] == 20) correct++;
  AddOffFirst(v, 2);
  if (v[3] == 45 && v[2] == 31) correct++;
  SubNeg(v, 1);
  if (v[0] == 7 && v[1] == 20) correct++;
  AddBig(big, 2);
  if (big[1002] == 1011 && big[1003] == 1003 && big[1001] == 1001) correct++;
  IncU8Off(b, 1);
  if (b[3] == 5 && b[4] == 5 && b[2] == 3) correct++;
  OrI16Off(h, 1);
  if (h[2] == 0x103 && h[3] == 4 && h[1] == 2) correct++;
  AddI32Off(w, 2);
  if (w[3] == 6 && w[2] == -3) correct++;
  AddField(pts, 1);
  if (pts[1].z == 14 && pts[1].w == 100 && pts[2].x == 2) correct++;

  /* The pointer is still intact afterwards */
  v[0] = 10; v[1] = 20; v[2] = 30; v[3] = 40;
  if (LdTwice(v, 1) == 60) correct++;
  if (LdThenP(v, 2) == 50) correct++;
  if (StThenP(v, 0, 99) == 10 && v[1] == 99 && v[2] == 30) correct++;
  if (StTwice(v, 1) == 10 && v[2] == 7 && v[3] == 8 && v[1] == 99) correct++;
  v[1] = 20; v[2] = 30; v[3] = 40;
  if (AddThenP(v, 1) == 30 && v[2] == 31) correct++;
  if (AddTwice(v, 0) == 22 && v[2] == 31) correct++;
  if (FieldTwice(pts, 2) == 0 && pts[2].z == 220 && pts[2].w == 200) correct++;

  /* Floats */
  if (LdF64Off(f, 2) == 3.5) correct++;
  StF64Off(f, 1, 9.5);
  if (f[2] == 9.5 && f[1] == 1.5 && f[3] == 3.5) correct++;
  if (LdField(pts, 2) == 2.25) correct++;
  StField(pts, 1, 7.75);
  if (pts[1].y == 7.75 && pts[1].x == 1 && pts[1].z == 14) correct++;

  /* Nothing else was written through a wild pointer */
  if (v[0] == 10 && v[3] == 40) correct++;
  if (b[0] == 1 && b[5] == 6 && h[0] == 1 && w[0] == -1 && w[1] == -2) correct++;
  if (f[0] == 0.5 && pts[0].z == 0 && pts[0].y == 0.25) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
