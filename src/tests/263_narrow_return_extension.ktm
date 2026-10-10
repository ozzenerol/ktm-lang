#include "testhelper.ktm"

/* A call returning a narrow integer (I8/I16/I32, U8/U16/U32) used directly
 * in an expression read the whole 64-bit return register. SysV and AAPCS64
 * leave the bits above the returned width undefined, so
 * `extern "c" I32 strcmp(...); strcmp("a", "b") < 0` was 0 (the -1 came
 * back as 0x00000000ffffffff) while going through an I32 variable worked.
 * The caller now sign- or zero-extends the result, for C functions, ktm
 * functions, function pointers and asm functions. C functions that return
 * a `long` are declared with narrower returns, and a ktm function
 * returning I64 is called through narrower function pointers, so there
 * are set bits above every width. */

extern "c" I32 strcmp(U8 *a, U8 *b);
extern "c" I8 atol(U8 *s);
extern "c" U8 atoll(U8 *s);
extern "c" I16 strtol(U8 *s, U8 **end, I64 base);
extern "c" U16 labs(I64 x);
extern "c" U32 llabs(I64 x);

I64 Wide(I64 x) { return x; }

I8  (*g_i8)(I64 x);
I16 (*g_i16)(I64 x);
I32 (*g_i32)(I64 x);
U8  (*g_u8)(I64 x);
U16 (*g_u16)(I64 x);
U32 (*g_u32)(I64 x);

/* ktm functions returning a value wider than their return type */
I32 TruncI32(I64 x) { return x; }
U16 TruncU16(I64 x) { return x; }
I8 TruncI8(I64 x) { return x; }

I64 Id(I64 x) { return x; }

#ifdef IS_X86_64
asm {
_T263_BIG::
    MOV     RAX, 0x12345678FFFFFFFE
    RET
}
public _extern _T263_BIG I32 AsmI32();
public _extern _T263_BIG U16 AsmU16();
#endif

I32 Main()
{
  "Test - narrow return values are extended: ";
  I64 tests = 0;
  I64 correct = 0;

  tests++;
  if (strcmp("a", "b") < 0 && strcmp("b", "a") > 0 && strcmp("a", "a") == 0 &&
      Id(strcmp("a", "b")) < 0 && strcmp("a", "b") + 0 < 0 && -strcmp("a", "b") > 0)
    correct++;
  else "Failed - strcmp: %d\n", strcmp("a", "b") < 0;

  /* C functions returning a long, declared narrower */
  tests++;
  if (atol("511") == -1 && atol("511") < 0 && atoll("511") == 255 &&
      strtol("1ffff", NULL, 16) == -1 && labs(0x7700000000010002) == 2 &&
      llabs(0x100000007) == 7 && llabs(0x1ffffffff) == 0xffffffff &&
      Id(atol("383")) == 127)
    correct++;
  else "Failed - C: %d %d %d %d %d\n", atol("511"), atoll("511"),
         strtol("1ffff", NULL, 16), labs(0x7700000000010002), llabs(0x100000007);

  g_i8 = (&Wide)(fn(I64) I8);
  g_i16 = (&Wide)(fn(I64) I16);
  g_i32 = (&Wide)(fn(I64) I32);
  g_u8 = (&Wide)(fn(I64) U8);
  g_u16 = (&Wide)(fn(I64) U16);
  g_u32 = (&Wide)(fn(I64) U32);
  I64 big = 0x7700000000000000;

  /* Signed: the low bits all ones are -1 */
  tests++;
  if (g_i8(big | 0xff) == -1 && g_i16(big | 0xffff) == -1 &&
      g_i32(big | 0xffffffff) == -1 && g_i8(big | 0x7f) == 127 &&
      g_i32(big | 0x80000000) < 0 && g_i16(big | 5) == 5)
    correct++;
  else "Failed - signed: %d %d %d\n", g_i8(big | 0xff), g_i16(big | 0xffff),
         g_i32(big | 0xffffffff);

  /* Unsigned: nothing above the width survives */
  tests++;
  if (g_u8(big | 0x1ff) == 0xff && g_u16(big | 0x1ffff) == 0xffff &&
      g_u32(big | 0x1ffffffff) == 0xffffffff && g_u8(big) == 0 &&
      g_u32(big | 7) + 1 == 8)
    correct++;
  else "Failed - unsigned: %x %x %x\n", g_u8(big | 0x1ff),
         g_u16(big | 0x1ffff), g_u32(big | 0x1ffffffff);

  /* In comparisons, arithmetic, as arguments and conditions */
  I64 n = 0;
  if (g_i32(big | 0xffffffff)) n++;
  if (!g_u8(big | 0x100)) n++;
  tests++;
  if (n == 2 && Id(g_i16(big | 0xfffe)) == -2 &&
      g_i8(big | 0xfe) * 3 == -6 && (g_u16(big | 0x10002) << 1) == 4)
    correct++;
  else "Failed - in expressions\n";

  /* ktm callees */
  tests++;
  if (TruncI32(0x1ffffffff) == -1 && TruncI32(0x100000005) == 5 &&
      TruncU16(0x3ffff) == 0xffff && TruncI8(0x180) == -128 &&
      TruncI32(0x1ffffffff) < 0)
    correct++;
  else "Failed - ktm: %d %d %d\n", TruncI32(0x1ffffffff), TruncU16(0x3ffff),
         TruncI8(0x180);

  /* Stored first, as before */
  I32 r = strcmp("a", "b");
  U8 u = g_u8(big | 0x1ff);
  tests++;
  if (r < 0 && u == 255) correct++;
  else "Failed - via a variable\n";

#ifdef IS_X86_64
  tests++;
  if (AsmI32() == -2 && AsmI32() < 0 && AsmU16() == 0xfffe) correct++;
  else "Failed - asm: %d %d\n", AsmI32(), AsmU16();
#endif

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
