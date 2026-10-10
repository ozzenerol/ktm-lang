#include "testhelper.ktm"

/* Assigning an integer constant to a narrower variable stored the
 * truncated value, but the assignment expression itself evaluated to the
 * untruncated constant: `I64 v = (u8 = 300);` gave v == 300, not 44. */

class Narrow
{
  U8 b;
  I16 h;
  U32 w;
};

U8 g_u8;
I32 g_i32;

I64 Id(I64 x) { return x; }

I32 Main()
{
  I64 correct = 0, tests = 24;

  "Test - assigning a constant to a narrow integer yields the truncated value: ";

  U8 u;
  I64 v = (u = 300);
  if (v == 44 && u == 44) correct++;

  /* Non-constant right side (already worked). */
  I64 w = 300;
  U8 u2;
  I64 v2 = (u2 = w);
  if (v2 == 44 && u2 == 44) correct++;

  /* Every narrow width, signed and unsigned, including negatives. */
  I8 c;
  if ((c = 200) == -56 && c == -56) correct++;
  U32 x;
  if ((x = 0x1FFFFFFFF) == 4294967295 && x == 4294967295) correct++;
  I16 s;
  if ((s = 40000) == -25536 && s == -25536) correct++;
  U16 us;
  if ((us = -1) == 65535 && us == 65535) correct++;
  I32 i3;
  if ((i3 = 0x180000000) == -2147483648 && i3 == -2147483648) correct++;
  if ((i3 = 0x7FFFFFFF) == 0x7FFFFFFF) correct++;
  U8 u3;
  if ((u3 = -1) == 255 && (u3 = 255) == 255) correct++;
  Bool b;
  /* Bool is 0 or 1 (value != 0), not the low byte (2) */
  if ((b = 258) == 1 && b == 1) correct++;

  /* Chained assignment through mixed widths. */
  U8 a;
  I16 bb;
  I64 r = (a = bb = 300);
  if (r == 44 && a == 44 && bb == 300) correct++;
  r = (bb = a = 300);
  if (r == 44 && a == 44 && bb == 44) correct++;

  /* As a condition: 256 truncates to 0. */
  I64 taken = 0;
  if ((u = 256)) taken = 1;
  if (!taken && u == 0) correct++;
  taken = 0;
  while ((u = 512)) { taken = 1; break; }
  if (!taken) correct++;

  /* Compound assignment. */
  u = 10;
  if ((u += 300) == 54 && u == 54) correct++;

  /* As a function argument and through memory targets. */
  if (Id((u = 300)) == 44) correct++;
  Narrow n;
  Narrow *np = &n;
  if ((n.b = 300) == 44 && n.b == 44) correct++;
  if ((np->h = 40000) == -25536 && n.h == -25536) correct++;
  if ((np->w = 0x3FFFFFFFF) == 4294967295 && n.w == 4294967295) correct++;
  U8 arr[2];
  if ((arr[1] = 257) == 1 && arr[1] == 1) correct++;
  if ((g_u8 = 300) == 44 && g_u8 == 44) correct++;
  if ((g_i32 = 0x1FFFFFFFF) == -1 && g_i32 == -1) correct++;

  /* 64-bit and float targets unchanged. */
  I64 big;
  if ((big = 0x123456789) == 0x123456789) correct++;
  F64 f;
  if ((f = 3) == 3.0) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
