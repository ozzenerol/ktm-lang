#include "testhelper.ktm"

/* An I8, U8 or Bool value couldn't index an array or be added to a
 * pointer ("`U8[8] + U8` is invalid": they are char-kind types, and only
 * int-kind ones were accepted), and for `buf[i]` the error pointed at an
 * earlier line, at whatever `+` came before. Every integer type is an
 * index and an offset now, zero- or sign-extended by its own type, and a
 * bad subscript is reported at the subscript. */

#define BAD_FILE "__387_bad.ktm"
#define BAD_OUT  "__387_bad.out"

class CIdx
{
  U8 u8;
  I8 i8;
  Bool b;
};

/* arr[k] holds k - 1024, so base = arr + 1024 has base[k] == k */
I32 arr[2048];
I32 *base;

I64 ByU8(U8 i)  { return base[i]; }
I64 ByI8(I8 i)  { return base[i]; }
I64 ByBool(Bool i) { return *(base + i); }

I64 Check(I64 got, I64 want, U8 *what)
{
  if (got == want) return 1;
  "\n  %s: got %d, want %d", what, got, want;
  return 0;
}

/* Compiles `src`; TRUE when it fails with the subscript error on `line` */
Bool BadSubscriptAt(U8 *src, I64 line)
{
  U8 *out, want[64];
  I64 res;
  Bool ok;
  Rm(BAD_FILE);
  FileWrite(BAD_FILE, src, StrLen(src));
  res = System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " BAD_FILE " > " BAD_OUT " 2>&1");
  out = FileRead(BAD_OUT);
  StrPrint(want, BAD_FILE ":%d:", line);
  ok = res != 0 && out && strstr(out, want) &&
       strstr(out, "Subscript expressions must evaluate to an integer type");
  if (!ok && out) "\n%s", out;
  Free(out);
  Rm(BAD_FILE);
  Rm(BAD_OUT);
  return ok;
}

I32 Main()
{
  "Test - I8/U8/Bool index and offset: ";
  I64 correct = 0, tests = 0, k;
  for (k = 0; k < 2048; k++) arr[k] = k - 1024;
  base = arr + 1024;

  U8 u8 = 255;  I8 i8 = -1;  Bool b = TRUE;
  U16 u16 = 65535 % 1000; I16 i16 = -2;
  U32 u32 = 1000;  I32 i32 = -3;
  U64 u64 = 7;  I64 i64 = -1000;

  /* every integer type as an index */
  tests++; correct += Check(base[u8], 255, "base[U8 255]");
  tests++; correct += Check(base[i8], -1, "base[I8 -1]");
  tests++; correct += Check(base[b], 1, "base[Bool TRUE]");
  tests++; correct += Check(base[u16], 535, "base[U16]");
  tests++; correct += Check(base[i16], -2, "base[I16]");
  tests++; correct += Check(base[u32], 1000, "base[U32]");
  tests++; correct += Check(base[i32], -3, "base[I32]");
  tests++; correct += Check(base[u64], 7, "base[U64]");
  tests++; correct += Check(base[i64], -1000, "base[I64]");

  /* and as a pointer offset, either side of `+`, and with `-` */
  tests++; correct += Check(*(base + u8), 255, "base + U8");
  tests++; correct += Check(*(u8 + base), 255, "U8 + base");
  tests++; correct += Check(*(base + i8), -1, "base + I8");
  tests++; correct += Check(*(base - i8), 1, "base - I8");
  tests++; correct += Check(*(base - u8), -255, "base - U8");
  tests++; correct += Check(*(base + b), 1, "base + Bool");

  /* the extremes, and values whose top bit is set */
  i8 = -128; u8 = 128;
  tests++; correct += Check(base[i8], -128, "base[I8 -128]");
  tests++; correct += Check(base[u8], 128, "base[U8 128]");
  i8 = 200; /* wraps to -56 */
  tests++; correct += Check(base[i8], -56, "base[I8 200]");
  tests++; correct += Check(base[255(U8)], 255, "base[255(U8)]");
  tests++; correct += Check(base[-1(I8)], -1, "base[-1(I8)]");

  /* from parameters, class fields and array elements */
  tests++; correct += Check(ByU8(250), 250, "U8 parameter");
  tests++; correct += Check(ByI8(-7), -7, "I8 parameter");
  tests++; correct += Check(ByBool(FALSE), 0, "Bool parameter");
  CIdx c;
  c.u8 = 240; c.i8 = -100; c.b = TRUE;
  tests++; correct += Check(base[c.u8], 240, "U8 field");
  tests++; correct += Check(base[c.i8], -100, "I8 field");
  tests++; correct += Check(base[c.b], 1, "Bool field");
  U8 bytes[2]; I8 sbytes[2];
  bytes[1] = 230; sbytes[1] = -9;
  tests++; correct += Check(base[bytes[1]], 230, "U8 element");
  tests++; correct += Check(base[sbytes[1]], -9, "I8 element");

  /* a U8 buffer indexed by a U8, read and written; += and -= */
  U8 buf[256], *p = buf;
  for (k = 0; k < 256; k++) buf[k] = k;
  u8 = 255; i8 = -1;
  buf[u8] = 'z';
  tests++; correct += Check(buf[255], 'z', "buf[U8] = 'z'");
  p += u8;
  tests++; correct += Check(p - buf, 255, "p += U8");
  p += i8;
  tests++; correct += Check(p - buf, 254, "p += I8");
  p -= b;
  tests++; correct += Check(p - buf, 253, "p -= Bool");
  tests++; correct += Check(p[i8], 252, "p[I8 -1]");

  /* a non-integer subscript is reported at the subscript, not the line
   * before (where the previous `+` is) */
  tests++;
  if (BadSubscriptAt(
"class C { I64 a; };
U0 Main()
{
  I64 x = 1 + 2;
  C c;
  U8 buf[4];
  buf[c] = 1;
}
", 7)) correct++;
  else "class subscript ";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
