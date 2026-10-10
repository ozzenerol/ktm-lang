#include "testhelper.ktm"

/* Taking the address of a parameter that has a default value stopped the
 * compiler (AOT and JIT) with "&AST_DEFAULT_PARAM not supported in
 * slice": references to such a parameter are wrapped in a default-param
 * node, which the address-of lowering didn't look through. It now works
 * like any other parameter, read and written through the pointer, with
 * or without the default, called with or without parentheses. */

class Pt
{
  I64 x, y;
};

Pt g_pt = {3, 4};

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

U0 SetI64(I64 *p, I64 v) { *p = v; }

I64 RdBool(Bool b = 1) { return (&b)(U8 *)[0]; }
I64 RwI64(I64 x = 41) { I64 *p = &x; *p += 1; return x; }
I64 RwI32(I32 v = 7) { I32 *p = &v; *p = *p * 3; return v; }
I64 RwU8(U8 c = 'a') { U8 *p = &c; *p += 1; return c; }
F64 RwF64(F64 f = 1.5) { F64 *p = &f; *p *= 2; return f; }
F64 RwF32(F32 f = 0.5) { F32 *p = &f; *p += 1; return f; }
U8 *RwPtr(U8 *s = "dflt") { U8 **pp = &s; *pp += 1; return s; }
I64 RwClassPtr(Pt *c = &g_pt)
{
  Pt **pc = &c;
  I64 sum = (*pc)->x + c->y;
  *pc = NULL;               /* the parameter itself changes */
  if (c != NULL) return -1;
  return sum;
}
I64 ViaHelper(I64 x = 5) { SetI64(&x, x * 10); return x; }
I64 Two(I64 a = 1, I64 b = 2)
{
  I64 *pa = &a, *pb = &b;
  *pa += 100;
  *pb += 200;
  return a * 1000 + b;
}

I32 Main()
{
  Pt q = {10, 20};
  "Test - address of a parameter with a default value: ";

  Check(RdBool == 1 && RdBool() == 1 && RdBool(0) == 0, "Bool read");
  Check(RwI64 == 42 && RwI64() == 42 && RwI64(9) == 10, "I64 read/write");
  Check(RwI32 == 21 && RwI32() == 21 && RwI32(2) == 6, "I32 read/write");
  Check(RwU8 == 'b' && RwU8() == 'b' && RwU8('x') == 'y', "U8 read/write");
  Check(RwF64 == 3.0 && RwF64() == 3.0 && RwF64(0.25) == 0.5,
      "F64 read/write");
  Check(RwF32 == 1.5 && RwF32() == 1.5 && RwF32(2.0) == 3.0,
      "F32 read/write");
  Check(!StrCmp(RwPtr, "flt") && !StrCmp(RwPtr(), "flt") &&
      !StrCmp(RwPtr("xyz"), "yz"), "pointer read/write");
  Check(RwClassPtr == 7 && RwClassPtr() == 7 && RwClassPtr(&q) == 30,
      "class pointer read/write");
  Check(ViaHelper == 50 && ViaHelper() == 50 && ViaHelper(3) == 30,
      "&param passed to a function");
  Check(Two == 101202 && Two() == 101202 && Two(5) == 105202 &&
      Two(5, 6) == 105206, "two defaulted params");
  Check(g_pt.x == 3 && g_pt.y == 4, "the default object is untouched");

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
