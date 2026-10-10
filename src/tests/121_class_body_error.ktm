#include "testhelper.ktm"

/* A syntax error inside a class/union body is reported where it is,
 * the rest of the body is still parsed, and the class keeps the fields
 * that did parse. ktm used to leave the class registered without a
 * field map and segfault (exit 139, no diagnostic) on the first use. */

#define CLS_ERR_FILE "__121_class_body_error.ktm"
#define CLS_ERR_OUT  "__121_class_body_error.out"

#define MSG_TOKEN_C   "Unexpected token 'c' while parsing class Foo, perhaps you meant to terminate the field with `;`?"
#define MSG_NO_C      "Property: c does not exist on class"

U0 CleanupClassErrTest(U0)
{
  Rm(CLS_ERR_FILE);
  Rm(CLS_ERR_OUT);
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

  CleanupClassErrTest;

  if (!FileWrite(CLS_ERR_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " CLS_ERR_FILE " > " CLS_ERR_OUT " 2>&1");

  out = FileRead(CLS_ERR_OUT);
  if (!out) {
    CleanupClassErrTest;
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
  CleanupClassErrTest;
  return ok;
}

Bool TestFieldAccess(U0)
{
  return CompileFailsExactly(
"class Foo { I64 a; I64 b c; };
U0 Main() { Foo f; f.a = 1; f.b = 2; }
", 1, MSG_TOKEN_C, "1:26");
}

/* Fields after the bad one are still parsed; the bad name is not a field */
Bool TestFieldsAfterError(U0)
{
  return CompileFailsExactly(
"class Foo { I64 a; I64 b c; I64 d; };
U0 Main() { Foo f; f.d = 1; f.c = 2; }
", 2, MSG_TOKEN_C, MSG_NO_C);
}

Bool TestThroughPointer(U0)
{
  return CompileFailsExactly(
"class Foo { I64 a; I64 b c; };
U0 Main() { Foo *p = NULL; p->a = 1; }
", 1, MSG_TOKEN_C, NULL);
}

Bool TestSizeof(U0)
{
  return CompileFailsExactly(
"class Foo { I64 a; I64 b c; };
U0 Main() { I64 n = sizeof(Foo); }
", 1, MSG_TOKEN_C, NULL);
}

Bool TestUnion(U0)
{
  return CompileFailsExactly(
"union U { I64 a; I64 b c; };
U0 Main() { U u; u.a = 1; }
", 1, "Unexpected token 'c' while parsing class U", NULL);
}

/* The anonymous nested class has no name; this used to crash in the
 * error path itself */
Bool TestNestedAnonymousClass(U0)
{
  return CompileFailsExactly(
"class Foo { I64 x; class { I64 a; I64 b c; }; I64 y; };
U0 Main() { Foo f; f.a = 1; f.x = 2; f.y = 3; }
", 1, "Unexpected token 'c' while parsing class <anonymous>", NULL);
}

Bool TestNestedAnonymousUnion(U0)
{
  return CompileFailsExactly(
"class Foo { I64 x; union { I64 a; I64 5; }; I64 y; };
U0 Main() { Foo f; f.a = 1; f.y = 2; }
", 1, "Unexpected character `5` while parsing class <anonymous>", NULL);
}

Bool TestAsFieldOfAnotherClass(U0)
{
  return CompileFailsExactly(
"class Foo { I64 a; I64 b c; };
class Bar { Foo f; I64 z; };
U0 Main() { Bar b; b.f.a = 1; b.z = 2; }
", 1, MSG_TOKEN_C, NULL);
}

Bool TestInherited(U0)
{
  return CompileFailsExactly(
"class Foo { I64 a; I64 b c; };
class B : Foo { I64 z; };
U0 Main() { B b; b.a = 1; b.z = 2; }
", 1, MSG_TOKEN_C, NULL);
}

Bool TestUnknownFieldType(U0)
{
  return CompileFailsExactly(
"class Foo { I64 a; Nope b; I64 c; };
U0 Main() { Foo f; f.c = 1; }
", 1, "Unexpected type `Nope` while parsing class Foo", NULL);
}

/* No body at all: the member access is an error, not a crash */
Bool TestForwardDeclared(U0)
{
  return CompileFailsExactly(
"class Foo;
U0 Main() { Foo *p; p->a = 1; }
", 1, "Cannot access `a`, Foo is incomplete", NULL);
}

I32 Main()
{
  "Test - Class body errors: \n";
  I64 tests = 11;
  I64 correct = 0;

  if (TestFieldAccess()) correct++;
  else "Failed - TestFieldAccess()\n";

  if (TestFieldsAfterError()) correct++;
  else "Failed - TestFieldsAfterError()\n";

  if (TestThroughPointer()) correct++;
  else "Failed - TestThroughPointer()\n";

  if (TestSizeof()) correct++;
  else "Failed - TestSizeof()\n";

  if (TestUnion()) correct++;
  else "Failed - TestUnion()\n";

  if (TestNestedAnonymousClass()) correct++;
  else "Failed - TestNestedAnonymousClass()\n";

  if (TestNestedAnonymousUnion()) correct++;
  else "Failed - TestNestedAnonymousUnion()\n";

  if (TestAsFieldOfAnotherClass()) correct++;
  else "Failed - TestAsFieldOfAnotherClass()\n";

  if (TestInherited()) correct++;
  else "Failed - TestInherited()\n";

  if (TestUnknownFieldType()) correct++;
  else "Failed - TestUnknownFieldType()\n";

  if (TestForwardDeclared()) correct++;
  else "Failed - TestForwardDeclared()\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
