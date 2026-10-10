#include "testhelper.ktm"

/* `(*fp)()` was an error ("The `*` operator cannot be applied to a
 * function pointer"). As in C, `*fp` of a function pointer is now the
 * function pointer itself, so `(*fp)()`, `(**fp)()`, `(*s.cb)(x)` and
 * `(*arr[i])(x)` all call it. `*` on an integer or a float is still an
 * error. */

#define FNDEREF_FILE "__214_fnderef.ktm"
#define FNDEREF_OUT  "__214_fnderef.out"
#define FNDEREF_BIN  "__214_fnderef.bin"

I64 g_hits;
U0 Hit() { g_hits++; }
I64 Add(I64 a, I64 b) { return a + b; }
I64 Mul(I64 a, I64 b) { return a * b; }
F64 Half(F64 x) { return x / 2.0; }
U8 *Name(I64 i) { if (i) return "one"; return "zero"; }
I64 Sum3(I64 a, I64 b=20, I64 c=300) { return a + b + c; }

class Ops
{
  I64 (*cb)(I64 a, I64 b);
  I64 k;
};

I64 (*g_op)(I64 a, I64 b) = &Mul;

I64 CallParam(I64 (*f)(I64 a, I64 b), I64 x)
{
  return (*f)(x, x) + (f)(1, 1);
}

U0 CleanupFiles()
{
  Rm(FNDEREF_FILE);
  Rm(FNDEREF_OUT);
  Rm(FNDEREF_BIN);
}

Bool CompileFailsWith(U8 *source, U8 *expected)
{
  CleanupFiles;
  if (!FileWrite(FNDEREF_FILE, source, StrLen(source),
                 O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  I64 res = System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                   "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                   FNDEREF_FILE " -o " FNDEREF_BIN " > " FNDEREF_OUT " 2>&1");
  Bool ok = res != 0;
  if (ok) {
    U8 *out = FileRead(FNDEREF_OUT);
    ok = out && strstr(out, expected) != NULL;
    if (!ok && out) "%s\n", out;
    Free(out);
  }
  CleanupFiles;
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - call through a dereferenced function pointer: ";

  U0 (*fp)() = &Hit;
  g_hits = 0;
  (*fp)();
  (**fp)();
  (***fp)();
  (fp)();
  tests++; if (g_hits == 4) correct++;
  else "(*fp)() did not call\n";

  I64 (*add)(I64 a, I64 b) = &Add;
  tests++; if ((*add)(2, 3) == 5 && (**add)(4, 5) == 9 && add(1, 1) == 2) correct++;
  else "arguments/return value wrong\n";

  F64 (*half)(F64 x) = &Half;
  U8 *(*name)(I64 i) = &Name;
  tests++; if ((*half)(5.0) == 2.5 && !StrCmp((*name)(1), "one") &&
               !StrCmp((*name)(0), "zero")) correct++;
  else "float/pointer return wrong\n";

  Ops ops;
  ops.cb = &Mul;
  Ops *op = &ops;
  tests++; if ((*ops.cb)(6, 7) == 42 && (*op->cb)(2, 8) == 16) correct++;
  else "(*s.cb)(x) wrong\n";

  I64 (*arr[2])(I64 a, I64 b);
  arr[0] = &Add;
  arr[1] = &Mul;
  I64 total = 0;
  for (I64 i = 0; i < 2; ++i) total += (*arr[i])(3, 4);
  tests++; if (total == 7 + 12) correct++;
  else "(*arr[i])(x) wrong\n";

  /* In larger expressions, through a global and a parameter */
  I64 x = (*add)(1, 2) * 10 + (*arr[1])(2, 2) - (*g_op)(3, 3);
  tests++; if (x == 30 + 4 - 9 && CallParam(&Add, 5) == 12) correct++;
  else "expression/global/param call wrong\n";

  /* `*fp` as a value is the function pointer */
  I64 (*copy)(I64 a, I64 b) = *add;
  I64 (*sum3)(I64 a, I64 b=20, I64 c=300) = &Sum3;
  tests++; if (copy(10, 20) == 30 && (*sum3)(1) == 321 && (*sum3)(1, 2) == 303) correct++;
  else "*fp as a value / default args wrong\n";

  /* A cast after a parenthesised function pointer is still a cast */
  tests++; if ((add)(I64) == (&Add)(I64)) correct++;
  else "(fp)(I64) is no longer a cast\n";

  tests++;
  if (CompileFailsWith("U0 Main() { I64 n = 5; I64 m = *n; }\n",
                       "The `*` operator cannot be applied")) correct++;
  else "`*` on an integer accepted\n";

  tests++;
  if (CompileFailsWith("U0 Main() { F64 f = 1.5; F64 g = *f; }\n",
                       "The `*` operator cannot be applied")) correct++;
  else "`*` on a float accepted\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
