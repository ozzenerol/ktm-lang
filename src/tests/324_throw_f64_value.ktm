#include "testhelper.ktm"

/* throw(42.75) passed the F64 in a float register, so KTM_Throw read
 * garbage from its integer argument register. The value is now
 * converted like an argument to a U64 parameter. */

U64 AsArg(U64 v)
{
  return v;
}

U64 Caught(F64 f)
{
  try {
    throw(f);
  } catch {
    return Fs->except_ch;
  }
  return 0;
}

U0 ThrowF32(F32 g)
{
  throw(g);
}

I32 Main()
{
  "Test - throw converts its value to U64: ";
  I64 correct = 0, tests = 14;
  F64 f = 42.75;
  F32 g = 7.5;
  F64 big = 3e18;
  I32 i32 = -5;
  I8 i8 = -2;
  U8 u8 = 200;
  U16 u16 = 65535;
  U32 u32 = 0xFFFFFFFF;
  U8 *p = "abc";
  I64 r = 7;

  try { throw(f); } catch if (Fs->except_ch == 42) correct++;
  try { throw(f); } catch if (Fs->except_ch == AsArg(f)) correct++;
  try { throw(f * 2); } catch if (Fs->except_ch == 85) correct++;
  try { throw(r + 0.5); } catch if (Fs->except_ch == 7) correct++;
  try { throw(1.99); } catch if (Fs->except_ch == 1) correct++;
  try { throw(big); } catch if (Fs->except_ch == 3000000000000000000) correct++;
  try { ThrowF32(g); } catch if (Fs->except_ch == 7) correct++;
  if (Caught(-2.5) == AsArg(-2.5)) correct++;

  try { throw(i32); } catch if (Fs->except_ch == AsArg(i32) && Fs->except_ch(I64) == -5) correct++;
  try { throw(i8); } catch if (Fs->except_ch(I64) == -2) correct++;
  try { throw(u8); } catch if (Fs->except_ch == 200) correct++;
  try { throw(u16); } catch if (Fs->except_ch == 65535) correct++;
  try { throw(u32); } catch if (Fs->except_ch == 0xFFFFFFFF) correct++;
  try { throw(p); } catch if (Fs->except_ch(U8 *) == p) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
