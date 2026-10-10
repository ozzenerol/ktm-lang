#include "testhelper.ktm"

/* Assigning a `U0 *` to a function pointer (or a function pointer to a
 * `U0 *`) warned "'U0 *' is not assignable to type '<fn> I64'". C
 * compilers accept this as an extension (POSIX dlsym depends on it), so
 * ktm accepts it silently; other pointer types still warn. */

#define VP_TEST_FILE "__168_void_fnptr.ktm"
#define VP_TEST_OUT  "__168_void_fnptr.out"

I64 Inc(I64 x) { return x + 1; }
I64 Twice(I64 x) { return x * 2; }

class C
{
  I64 (*f)(I64 x);
};

I64 Call(I64 (*fn)(I64 x), I64 v) { return fn(v); }

U0 CleanupVpTest()
{
  Rm(VP_TEST_FILE);
  Rm(VP_TEST_OUT);
  Rm("./a.out");
}

/* Compiles `body` inside Main; returns the compiler output (or NULL) */
U8 *CompileOutput(U8 *body)
{
  U8 *src = MStrPrint("I64 Inc(I64 x) { return x + 1; }\n"
                      "class C { I64 (*f)(I64 x); };\n"
                      "I64 Call(I64 (*fn)(I64 x), I64 v) { return fn(v); }\n"
                      "U0 Main()\n{\n  U0 *v = &Inc;\n  I64 (*fp)(I64 x) = &Inc;\n"
                      "  C c;\n  I64 *ip = NULL;\n%s\n}\n", body);
  U8 *out = NULL;
  CleanupVpTest;
  if (FileWrite(VP_TEST_FILE, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC)) {
    System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " VP_TEST_FILE " > " VP_TEST_OUT " 2>&1");
    out = FileRead(VP_TEST_OUT);
  }
  Free(src);
  CleanupVpTest;
  return out;
}

Bool Silent(U8 *body)
{
  U8 *out = CompileOutput(body);
  Bool ok = out && !strstr(out, "warning") && !strstr(out, "error");
  if (out && !ok) "%s\n", out;
  Free(out);
  return ok;
}

Bool Warns(U8 *body)
{
  U8 *out = CompileOutput(body);
  Bool ok = out && strstr(out, "Incompatible types") != NULL;
  if (out && !ok) "%s\n", out;
  Free(out);
  return ok;
}

I32 Main()
{
  "Test - U0 * and function pointers: ";
  I64 correct = 0, tests = 0;

  /* Behaviour */
  U0 *v = &Inc;
  I64 (*fp)(I64 x) = v;
  tests++; if (fp(1) == 2) correct++;
  v = &Twice;
  fp = v;
  tests++; if (fp(4) == 8) correct++;
  C c;
  c.f = v;
  tests++; if (c.f(5) == 10) correct++;
  U0 *w = c.f;
  fp = w;
  tests++; if (fp(3) == 6) correct++;
  tests++; if (Call(v, 6) == 12) correct++;

  /* No diagnostics */
  tests++; if (Silent("  I64 (*g)(I64 x) = v;")) correct++;
  tests++; if (Silent("  fp = v;")) correct++;
  tests++; if (Silent("  v = fp;")) correct++;
  tests++; if (Silent("  U0 *w = fp;")) correct++;
  tests++; if (Silent("  c.f = v;  v = c.f;")) correct++;
  tests++; if (Silent("  Call(v, 1);")) correct++;

  /* Other pointer types still warn */
  tests++; if (Warns("  fp = ip;")) correct++;
  tests++; if (Warns("  ip = fp;")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
