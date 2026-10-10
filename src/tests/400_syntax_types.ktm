#include "testhelper.ktm"

/* ktm syntax v0.2: void i8 u8 i16 u16 i32 u32 i64 u64 f32 f64 bool are
 * the built-in types (the old names U0 I8 ... F64 Bool still work and are
 * the same types). They are type names, not keywords: a struct field can
 * still be called i64. typeof and diagnostics print the new names. */

class Mix
{
  i8 a;
  u8 b;
  i16 c;
  u16 d;
  i32 e;
  u32 f;
  i64 g;
  u64 h;
  f32 x;
  f64 y;
  bool z;
  i64 i64;       /* a field may use a type's name */
  f64 f64;
};

i64 g_count = 3;
u8 *g_name = "ktm";

i64 Add(i64 a, i64 b = 10) { return a + b; }
f64 Half(f64 v) { return v / 2; }
bool IsNeg(i32 v) { return v < 0; }
void Bump(i64 *p) { *p += 1; }
u8 *Name() { return g_name; }

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - lowercase built-in types: ";

  tests++;
  if (sizeof(i8) == 1 && sizeof(u8) == 1 && sizeof(i16) == 2 && sizeof(u16) == 2 &&
      sizeof(i32) == 4 && sizeof(u32) == 4 && sizeof(i64) == 8 && sizeof(u64) == 8 &&
      sizeof(f32) == 4 && sizeof(f64) == 8 && sizeof(bool) == 1) correct++;
  else "\n  sizes\n";

  tests++;   /* signedness and wrap-around, as the old names */
  i8 sc = 200;
  u8 uc = 200;
  i16 ss = 40000;
  u16 us = 70000;
  i32 si = 0xFFFFFFFF;
  u32 ui = 0xFFFFFFFF;
  u64 big = -1;
  if (sc == -56 && uc == 200 && ss == -25536 && us == 4464 && si == -1 &&
      ui == 0xFFFFFFFF && big > 0) correct++;
  else "\n  signedness\n";

  tests++;   /* bool converts like C's _Bool */
  bool t = 0x800, f = 0;
  if (t == TRUE && f == FALSE && IsNeg(-4) && !IsNeg(4)) correct++;
  else "\n  bool\n";

  tests++;   /* functions, defaults, pointers, globals */
  i64 n = 1;
  Bump(&n);
  if (Add(1) == 11 && Add(1, 2) == 3 && Half(5) == 2.5 && n == 2 &&
      g_count == 3 && !StrCmp(Name(), "ktm")) correct++;
  else "\n  functions\n";

  tests++;   /* struct fields, including fields named like types */
  Mix m;
  m.i64 = 42;
  m.f64 = 1.25;
  m.z = 7;
  if (m.i64 == 42 && m.f64 == 1.25 && m.z == 1 && sizeof(Mix) >= 50) correct++;
  else "\n  struct fields\n";

  tests++;   /* arrays and casts */
  i32 arr[4] = {1, 2, 3, 4};
  f64 avg = (arr[0] + arr[1] + arr[2] + arr[3])(f64) / 4;
  if (avg == 2.5 && sizeof(arr) == 16) correct++;
  else "\n  arrays and casts\n";

  tests++;   /* old and new names are the same types */
  I64 old_i = 5;
  i64 *pi = &old_i;
  U8 *old_s = "x";
  u8 *ps = old_s;
  if (*pi == 5 && *ps == 'x' && sizeof(I64) == sizeof(i64)) correct++;
  else "\n  old names\n";

  tests++;   /* typeof prints the new names */
  if (!StrCmp(typeof(old_i), "i64") && !StrCmp(typeof(Half(1.0)), "f64") &&
      !StrCmp(typeof(t), "bool") && !StrCmp(typeof(ps), "u8 *") &&
      !StrCmp(typeof(arr), "i32[4]") && !StrCmp(typeof(sc), "i8")) correct++;
  else "\n  typeof: %s %s %s %s %s\n", typeof(old_i), typeof(Half(1.0)), typeof(t), typeof(ps), typeof(arr);

  tests++;   /* and so do diagnostics */
  U8 *src = "I64 g;\nU0 A() { return g; }\nU0 Main() { A(); }\n";
  FileWrite("__400_warn.ktm", src, StrLen(src));
  U8 *out = Shlurp("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit __400_warn.ktm 2>&1");
  if (out && strstr(out, "of type 'i64' expected 'void'")) correct++;
  else "\n  diagnostic names: [%s]\n", out;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
