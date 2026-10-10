#include "testhelper.ktm"

/* A narrowing cast (to I8/U8/I16/U16/I32/U32, and Bool) truncated its value
 * but did not sign or zero extend the result before it was used, so a
 * signed cast kept its raw low bits: `200(I8) == -56` was 0 although the
 * value prints as -56, and the same for a variable, a prefix cast, a
 * switch, an argument, arithmetic and a return. The cast now yields the
 * canonical full-width value. */

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

I64 TakeI64(I64 x) { return x; }
I64 RetI8(I64 x) { return x(I8); }
I64 RetU8(I64 x) { return x(U8); }
I64 RetI16(I64 x) { return x(I16); }
I64 RetU32(I64 x) { return x(U32); }

U0 Signed()
{
  I64 v = 200;                 /* low byte 0xC8 = -56 as I8 */
  I64 w = 0x1FF80;             /* low 16 bits 0xFF80 = -128 as I16 */
  I64 q = 0x1FFFFFF80;         /* low 32 bits = -128 as I32 */

  Check(200(I8) == -56, "const I8 compare");
  Check(v(I8) == -56, "var I8 compare");
  Check(v(I8) < 0, "I8 is negative");
  Check(v(I8) + 0 == -56, "I8 in arithmetic");
  Check(v(I8) * 2 == -112, "I8 multiplied");
  Check(TakeI64(v(I8)) == -56, "I8 as an argument");
  Check(RetI8(v) == -56, "I8 returned");

  Check(0x1FF80(I16) == -128, "const I16 compare");
  Check(w(I16) == -128, "var I16 compare");
  Check(RetI16(w) == -128, "I16 returned");

  Check(q(I32) == -128, "const I32 compare");
  Check(q(I32) + 28 == -100, "I32 in arithmetic");

  "switch on v(I8): ";
  switch (v(I8)) {
    case -56: Check(TRUE, "I8 switch matches -56"); break;
    default:  Check(FALSE, "I8 switch matches -56");
  }

  /* Round trip through a narrow slot keeps the value. */
  I8 slot = v(I8);
  Check(slot == -56, "stored into an I8 slot and read back");
}

U0 Unsigned()
{
  I64 w = 0x1FF;               /* low byte 0xFF = 255 */
  I64 h = 0x1FFFF;             /* low 16 bits = 0xFFFF */
  I64 d = 0x1FFFFFFFF;         /* low 32 bits = 0xFFFFFFFF */

  Check(511(U8) == 255, "const U8 compare");
  Check(w(U8) == 255, "var U8 compare");
  Check(w(U8) > 0, "U8 is positive");
  Check(w(U8) + 1 == 256, "U8 in arithmetic");
  Check(RetU8(w) == 255, "U8 returned");
  Check(0x1FFFF(U16) == 0xFFFF, "const U16 compare");
  Check(h(U16) == 0xFFFF, "var U16 compare");
  Check(d(U32) == 0xFFFFFFFF, "var U32 compare");
  Check(RetU32(d) == 0xFFFFFFFF, "U32 returned");
}

/* The rc has C prefix casts (T)x too; they lower the same way. */
U0 Prefix()
{
  I64 v = 200, w = 0x1FF;
  Check((I8)v == -56, "prefix (I8) compare");
  Check((I8)v + 0 == -56, "prefix (I8) in arithmetic");
  Check((I8)200 == -56, "prefix (I8) on a constant");
  Check((U8)w == 255, "prefix (U8) compare");
  Check((I16)0x1FF80 == -128, "prefix (I16) compare");
  Check((I32)0x1FFFFFF80 == -128, "prefix (I32) compare");
}

U0 Bools()
{
  I64 v = 200, z = 0;
  Check(200(Bool) == TRUE, "const nonzero to Bool is TRUE");
  Check(v(Bool) == TRUE, "var nonzero to Bool is TRUE");
  Check(z(Bool) == FALSE, "zero to Bool is FALSE");
  Check(TakeI64(v(Bool)) == 1, "Bool as an argument is 1");
}

I32 Main()
{
  "Test - narrowing cast then use: ";
  Signed();
  Unsigned();
  Prefix();
  Bools();
  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
