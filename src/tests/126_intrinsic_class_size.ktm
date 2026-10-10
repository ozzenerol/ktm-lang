#include "testhelper.ktm"

/* An intrinsic class whose fields don't fit its base type, or whose base
 * type is not an integer, is reported once. ktm used to unwind before
 * the class got its field map, so every later member access added a
 * "Cannot access `a`, X is incomplete" error. A class body cut off by
 * the end of input is reported as that, not as an unexpected token. */

#define INTR_FILE "__126_intrinsic_class_size.ktm"
#define INTR_OUT  "__126_intrinsic_class_size.out"

#define MSG_EOF "Unexpected end of input while parsing class Foo"

U16 class Pair126
{
  U8 lo;
  U8 hi;
};

I64 class Full126
{
  I32 a;
  U16 b;
  U8 c;
  U8 d;
};

U0 CleanupIntrinsicTest(U0)
{
  Rm(INTR_FILE);
  Rm(INTR_OUT);
  Rm("./a.out");
}

I64 CountErrors(U8 *out)
{
  I64 n = 0;
  U8 *p = out;
  while ((p = strstr(p, "error: "))) {
    n++;
    p += 7;
  }
  return n;
}

/* Compile `source`: ktm must exit with status 1 (not crash) and print
 * exactly `n_expected` errors, `m1`/`m2` (when not NULL) among them. */
Bool CompileFailsExactly(U8 *source, I64 n_expected, U8 *m1, U8 *m2)
{
  U8 *out;
  I64 res;
  Bool ok;

  CleanupIntrinsicTest;

  if (!FileWrite(INTR_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " INTR_FILE " > " INTR_OUT " 2>&1");

  out = FileRead(INTR_OUT);
  if (!out) {
    CleanupIntrinsicTest;
    return FALSE;
  }

  /* System gives the exit code: 1 for a compile error; a segfault
   * would be 139 (from the shell) or -1 (killed by a signal). */
  ok = res == 1;
  if (CountErrors(out) != n_expected) ok = FALSE;
  if (m1 && !strstr(out, m1)) ok = FALSE;
  if (m2 && !strstr(out, m2)) ok = FALSE;
  if (!ok) "status %d\n%s\n", res, out;

  Free(out);
  CleanupIntrinsicTest;
  return ok;
}

Bool TestTooBig(U0)
{
  return CompileFailsExactly(
"I8 class X { I64 a; };
U0 Main() { X x; x.a = 1; }
Main;
", 1, "Fields of intrinsic class X take 8 bytes, more than the 1 bytes of its base type I8", NULL);
}

Bool TestOneByteOver(U0)
{
  return CompileFailsExactly(
"U16 class Z { U8 a; U8 b; U8 c; };
U0 Main() { Z z; z.a = 1; z.c = 3; \"%d\\n\", z.b; }
Main;
", 1, "Fields of intrinsic class Z take 3 bytes, more than the 2 bytes of its base type U16", NULL);
}

/* Each bad class is reported, and nothing else */
Bool TestTwoClasses(U0)
{
  return CompileFailsExactly(
"U16 class Z { U8 a; U8 b; U8 c; };
I32 class W { I64 a; };
U0 Main() { Z z; W w; z.a = 1; w.a = 2; }
Main;
", 2, "intrinsic class Z take 3 bytes", "intrinsic class W take 8 bytes");
}

Bool TestNonIntegerBase(U0)
{
  return CompileFailsExactly(
"F64 class Y { I64 a; };
U0 Main() { Y y; y.a = 1; }
Main;
", 1, "Can only make intrinsic types from integer types, got F64", NULL);
}

/* A class body cut off by the end of input, after a field name, an
 * array dimension, a pointer field, a `,` and a field type. */
Bool TestEofAfterName(U0)
{
  return CompileFailsExactly("class Foo { I64 a; I64 b", 1, MSG_EOF, NULL);
}

Bool TestEofAfterDimension(U0)
{
  return CompileFailsExactly("class Foo { I64 a; I64 b[2]", 1, MSG_EOF, NULL);
}

Bool TestEofAfterPointer(U0)
{
  return CompileFailsExactly("class Foo { I64 a; I64 *b", 1, MSG_EOF, NULL);
}

Bool TestEofAfterComma(U0)
{
  return CompileFailsExactly("class Foo { I64 a; I64 b,", 1, MSG_EOF, NULL);
}

Bool TestEofAfterType(U0)
{
  return CompileFailsExactly("class Foo { I64 a; I64", 1, MSG_EOF, NULL);
}

Bool TestEofAfterField(U0)
{
  return CompileFailsExactly("class Foo { I64 a; I64 b;", 1,
      "Unexpected end of input in class/union body", NULL);
}

/* Intrinsic classes whose fields fit still work */
Bool TestFits(U0)
{
  Pair126 p;
  Full126 f;
  p.lo = 1;
  p.hi = 2;
  f.a = -5;
  f.b = 600;
  f.c = 7;
  f.d = 8;
  return sizeof(Pair126) == 2 && p == 0x0201 && p.lo == 1 && p.hi == 2 &&
         sizeof(Full126) == 8 && f.a == -5 && f.b == 600 &&
         f.c == 7 && f.d == 8;
}

I32 Main()
{
  "Test - Intrinsic class size errors: \n";
  I64 tests = 11;
  I64 correct = 0;

  if (TestTooBig()) correct++;
  else "Failed - TestTooBig()\n";

  if (TestOneByteOver()) correct++;
  else "Failed - TestOneByteOver()\n";

  if (TestTwoClasses()) correct++;
  else "Failed - TestTwoClasses()\n";

  if (TestNonIntegerBase()) correct++;
  else "Failed - TestNonIntegerBase()\n";

  if (TestEofAfterName()) correct++;
  else "Failed - TestEofAfterName()\n";

  if (TestEofAfterDimension()) correct++;
  else "Failed - TestEofAfterDimension()\n";

  if (TestEofAfterPointer()) correct++;
  else "Failed - TestEofAfterPointer()\n";

  if (TestEofAfterComma()) correct++;
  else "Failed - TestEofAfterComma()\n";

  if (TestEofAfterType()) correct++;
  else "Failed - TestEofAfterType()\n";

  if (TestEofAfterField()) correct++;
  else "Failed - TestEofAfterField()\n";

  if (TestFits()) correct++;
  else "Failed - TestFits()\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
