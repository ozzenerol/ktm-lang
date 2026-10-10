#include "testhelper.ktm"

/* A definition (or a later prototype) dropped the default arguments of
 * the earlier prototype: `I64 F(I64 *v, I64 n=2);` then
 * `I64 F(I64 *v, I64 n) {...}` made `F(a)` fail with "Unexpected number
 * of arguments 1 in call to F(), expected 2 args". Defaults from the
 * earlier declaration now carry over (as in C++); repeating the same
 * default is fine, a different one is an error. */

#define DEFARGS_FILE "__213_defargs.ktm"
#define DEFARGS_OUT  "__213_defargs.out"
#define DEFARGS_BIN  "__213_defargs.bin"

I64 Sum(I64 *v, I64 n=2);
F64 Scale(F64 x, F64 by=2.5);
U8 *Greet(U8 *name="world");
I64 Mid(I64 a, I64 b=10, I64 c=5);
I64 Twice(I64 y) { return y * 2; }
I64 Apply(I64 x, I64 (*f)(I64 y)=&Twice);
I64 Same(I64 n=4);
I64 Twice2(I64 a=1, I64 b=2);
I64 Twice2(I64 a, I64 b);          /* a second prototype keeps them */

/* std.ktmh prototypes: redeclaring without the default keeps it */
public IntVec *IntVecNew(I64 size);
public U8 *StrTrimStart(U8 *str, I64 str_len, I64 *_new_len);

I64 CallsBefore()
{// calls before the definition use the prototype, as before
  I64 v[3] = {1, 2, 3};
  return Sum(v);
}

I64 Sum(I64 *v, I64 n)
{
  I64 total = 0;
  for (I64 i = 0; i < n; ++i) total += v[i];
  return total;
}

F64 Scale(F64 x, F64 by) { return x * by; }
U8 *Greet(U8 *name) { return name; }
I64 Mid(I64 a, I64 b, I64 c) { return a * 100 + b * 10 + c; }
I64 Apply(I64 x, I64 (*f)(I64 y)) { return f(x); }
I64 Same(I64 n=4) { return n; }
I64 Twice2(I64 a, I64 b) { return a * 10 + b; }

I64 PlusOne(I64 y) { return y + 1; }

U0 CleanupFiles()
{
  Rm(DEFARGS_FILE);
  Rm(DEFARGS_OUT);
  Rm(DEFARGS_BIN);
}

/* Compile `source` with ktm; TRUE if the result matches `should_fail`
 * and, when `expected` is given, the output contains it. */
Bool Compile(U8 *source, Bool should_fail, U8 *expected)
{
  CleanupFiles;
  if (!FileWrite(DEFARGS_FILE, source, StrLen(source),
                 O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  I64 res = System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                   "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                   DEFARGS_FILE " -o " DEFARGS_BIN " > " DEFARGS_OUT " 2>&1");
  Bool ok = (res != 0) == should_fail;
  if (ok && expected) {
    U8 *out = FileRead(DEFARGS_OUT);
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
  I64 v[4] = {1, 2, 3, 4};
  "Test - prototype default arguments: ";

  tests++; if (Sum(v) == 3 && Sum(v, 4) == 10 && CallsBefore() == 3) correct++;
  else "int default lost\n";
  tests++; if (Scale(2.0) == 5.0 && Scale(2.0, 3.0) == 6.0) correct++;
  else "float default lost\n";
  tests++; if (!StrCmp(Greet(), "world") && !StrCmp(Greet("ktm"), "ktm")) correct++;
  else "string default lost\n";
  tests++; if (Mid(1) == 205 && Mid(1, 2) == 125 && Mid(1, 2, 3) == 123) correct++;
  else "second default lost\n";
  tests++; if (Apply(5) == 10 && Apply(5, &PlusOne) == 6) correct++;
  else "function pointer default lost\n";
  tests++; if (Same() == 4 && Same(7) == 7) correct++;
  tests++; if (Twice2() == 12 && Twice2(3) == 32 && Twice2(3, 4) == 34) correct++;
  else "second prototype dropped the defaults\n";

  IntVec *iv = IntVecNew();
  I64 trimmed_len = 0;
  U8 *t = StrTrimStart(StrNew("  x"), 3, &trimmed_len);
  U8 *t2 = StrTrimStart(StrNew("  y"));
  tests++; if (iv && iv->size == 0 && !StrCmp(t, "x") && !StrCmp(t2, "y") && trimmed_len == 1) correct++;
  else "std.ktmh default lost\n";
  IntVecRelease(iv);
  Free(t);
  Free(t2);

  tests++;
  if (Compile("I64 F(I64 n=2);\nI64 F(I64 n=3) { return n; }\n"
              "I32 Main() { return F(); }\n", TRUE,
              "default argument `3` for parameter `n` of `F` does not match")) {
    correct++;
  } else "different default accepted\n";

  tests++;
  if (Compile("public IntVec *IntVecNew(I64 size=64);\n"
              "I32 Main() { IntVecNew(); return 0; }\n", TRUE,
              "does not match the earlier declaration's `32`")) {
    correct++;
  } else "different std.ktmh default accepted\n";

  tests++;
  if (Compile("I64 F(I64 n=1+1);\nI64 F(I64 n=2) { return n; }\n"
              "I32 Main() { return F() - 2; }\n", FALSE, NULL)) {
    correct++;
  } else "equal constant defaults rejected\n";

  tests++;
  if (Compile("I64 F(I64 a, I64 n=2);\nI64 F(I64 n) { return n; }\n"
              "I32 Main() { return 0; }\n", TRUE, "conflicting types for `F`")) {
    correct++;
  } else "conflicting prototype not reported\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
