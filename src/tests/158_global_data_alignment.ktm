#include "testhelper.ktm"

/* Initialised globals were laid out back to back in .data with no
 * alignment directive, so an I64 / F64 / class / pointer global after a
 * `U8 a[3]` landed on an odd address. Each global is now aligned to its
 * type's alignment: a class by its widest field, an array by its element. */

class Wide
{
  I32 a;
  I64 b;
};

class Narrow
{
  I32 a;
  I32 b;
};

class Half
{
  I16 a;
  U8 b;
};

U8 pad0[3] = {1, 2, 3};
I64 g64 = 4;
U8 pad1[3] = {1, 2, 3};
F64 gf64[2] = {1.5, 2.5};
U8 pad2[1] = {1};
Wide gwide = {1, 2};
U8 pad3[1] = {1};
U8 *gptr[1] = {"x"};
U8 pad4[1] = {1};
I32 g32[2] = {1, 2};
U8 pad5[1] = {1};
Narrow gnarrow = {3, 4};
U8 pad6[1] = {1};
I16 g16[2] = {1, 2};
U8 pad7[1] = {1};
Half ghalf = {5, 6};
U8 pad8[1] = {1};
F32 gf32[1] = {1.5};
U8 pad9[1] = {1};
Wide gwides[2] = {{1, 2}, {3, 4}};

/* Zero-initialised (.bss / common) globals. */
U8 bpad0[3];
I64 b64;
U8 bpad1[3];
Wide bwide;
U8 bpad2[1];
I32 b32;
U8 bpad3[1];
I16 b16;

I64 Mis(U0 *p, I64 align)
{
  return p(I64) & (align - 1);
}

I64 StaticMis()
{
  static U8 spad0[3] = {1, 2, 3};
  static I64 s64[1] = {7};
  static U8 spad1[3] = {1, 2, 3};
  static Wide swide = {1, 2};
  static U8 spad2[3];
  static I64 sb64;
  static U8 spad3[1];
  static I32 sb32;
  static U8 spad4[3] = {1, 2, 3};
  static F64 sf64 = 1.5;
  return Mis(s64, 8) + Mis(&swide, 8) + Mis(&sb64, 8) + Mis(&sb32, 4) +
         Mis(&sf64, 8) + s64[0] - 7 + swide.b - 2 + (sf64 != 1.5);
}

I32 Main()
{
  I64 correct = 0, tests = 17;

  "Test - global data alignment: ";

  if (Mis(&g64, 8) == 0 && g64 == 4) correct++;
  if (Mis(gf64, 8) == 0 && gf64[1] == 2.5) correct++;
  if (Mis(&gwide, 8) == 0 && gwide.b == 2) correct++;
  if (Mis(gptr, 8) == 0 && gptr[0][0] == 'x') correct++;
  if (Mis(g32, 4) == 0 && g32[1] == 2) correct++;
  if (Mis(&gnarrow, 4) == 0 && gnarrow.b == 4) correct++;
  if (Mis(g16, 2) == 0 && g16[1] == 2) correct++;
  if (Mis(&ghalf, 2) == 0 && ghalf.b == 6) correct++;
  if (Mis(gf32, 4) == 0 && gf32[0] == 1.5) correct++;
  if (Mis(gwides, 8) == 0 && gwides[1].b == 4) correct++;

  if (Mis(&b64, 8) == 0) correct++;
  if (Mis(&bwide, 8) == 0) correct++;
  if (Mis(&b32, 4) == 0) correct++;
  if (Mis(&b16, 2) == 0) correct++;

  if (StaticMis() == 0) correct++;

  /* The bytes around them are intact. */
  if (pad0[2] == 3 && pad1[0] == 1 && pad9[0] == 1) correct++;
  if (gwides[0].a == 1 && gnarrow.a == 3 && ghalf.a == 5) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
