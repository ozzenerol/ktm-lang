#include "testhelper.ktm"

/* Default arguments declared on a class field function pointer were
 * ignored: `c.f(1)` with `I64 (*f)(I64 a, I64 b=10)` passed whatever was
 * in the register for `b` instead of 10. Plain and local function
 * pointers already filled them in. */

#define FIELD_FNPTR_TEST_FILE "__108_field_fnptr_fail.ktm"
#define FIELD_FNPTR_TEST_OUT  "__108_field_fnptr_fail.out"

class C
{
  I64 (*f)(I64 a, I64 b=10);
  I64 (*all)(I64 a=1, I64 b=2);
  F64 (*fl)(F64 x, F64 y=2.5, F64 z=1);
  U8 *(*s)(U8 *a, U8 *b="world");
  I64 v;
};

class Outer
{
  C in;
  C *pin;
};

I64 Add(I64 a, I64 b) { return a + b; }
I64 Sub(I64 a, I64 b) { return a - b; }
F64 FAdd(F64 x, F64 y, F64 z) { return x + y + z; }
U8 *Join(U8 *a, U8 *b) { return MStrPrint("%s %s", a, b); }

Bool CompileFailsWith(U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;
  Bool ok = FALSE;

  if (!FileWrite(FIELD_FNPTR_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " FIELD_FNPTR_TEST_FILE " > " FIELD_FNPTR_TEST_OUT " 2>&1");
  if (res != 0) {
    out = FileRead(FIELD_FNPTR_TEST_OUT);
    if (out) {
      if (strstr(out, expected)) ok = TRUE;
      else "%s\n", out;
      Free(out);
    }
  }
  Rm(FIELD_FNPTR_TEST_FILE);
  Rm(FIELD_FNPTR_TEST_OUT);
  Rm("./a.out");
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 14;
  U8 *str;

  "Test - default arguments through class field function pointers: ";

  C c;
  c.f = &Add;
  c.all = &Sub;
  c.fl = &FAdd;
  c.s = &Join;

  /* the original report */
  if (c.f(1) == 11) correct++;
  /* explicit argument overrides the default */
  if (c.f(1, 2) == 3) correct++;
  /* every argument defaulted, with and without a skipped slot */
  if (c.all() == -1) correct++;
  if (c.all(5) == 3) correct++;
  if (c.all(, 7) == -6) correct++;

  /* F64 defaults, including an integer literal default for an F64 */
  if (c.fl(1.0) == 4.5) correct++;
  if (c.fl(1.0, 0.5) == 2.5) correct++;

  /* string default */
  str = c.s("hello");
  if (!StrCmp(str, "hello world")) correct++;
  Free(str);

  /* through a pointer to the class */
  C *cp = &c;
  if (cp->f(2) == 12) correct++;
  if (cp->all() == -1) correct++;

  /* nested class fields, by value and by pointer */
  Outer o;
  o.in.f = &Add;
  o.pin = &c;
  if (o.in.f(3) == 13) correct++;
  if (o.pin->f(4) == 14) correct++;
  if (o.pin->fl(0.0, 1.0) == 2.0) correct++;

  /* a field without defaults still rejects too few arguments */
  if (CompileFailsWith(
"
I64 Add(I64 a, I64 b) { return a + b; }
class C { I64 (*f)(I64 a, I64 b); };
U0 Main() { C c; c.f = &Add; c.f(1); }
",
      "Unexpected number of arguments 1 in call to f(), expected 2 args")) {
    correct++;
  }

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
