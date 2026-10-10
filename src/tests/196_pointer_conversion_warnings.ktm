#include "testhelper.ktm"

/* `I64 *q; q -= q;` (a pointer difference, an integer, stored back into
 * the pointer) and `F64 f; I64 *q = &f;` (incompatible pointer types)
 * compiled with no diagnostic. Both now warn, as C does, at the
 * operator; so do `q = &f`, passing `&f` for an `I64 *` parameter and
 * returning an `F64 *` as an `I64 *`. They stay warnings: the program
 * still builds and runs. `U0 *`, byte pointers, same types, `q += 1`,
 * `I64 d = q - q` and explicit casts don't warn. */

#define PTRCONV_FILE "__196_ptrconv.ktm"
#define PTRCONV_OUT  "__196_ptrconv.out"
#define PTRCONV_RUN  "__196_ptrconv.run"

U0 CleanupPtrConvTest(U0)
{
  Rm(PTRCONV_FILE);
  Rm(PTRCONV_OUT);
  Rm(PTRCONV_RUN);
  Rm("./a.out");
}

/* Compile `source` with ktm; returns the compiler output (NULL if the
 * compile failed) and leaves ./a.out */
U8 *CompileOutput(U8 *source)
{
  I64 res;

  CleanupPtrConvTest;
  if (!FileWrite(PTRCONV_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return NULL;
  }
  res = System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               PTRCONV_FILE " > " PTRCONV_OUT " 2>&1");
  if (res != 0) {
    return NULL;
  }
  return FileRead(PTRCONV_OUT);
}

/* Run ./a.out and check what it prints */
Bool RunPrints(U8 *expected)
{
  U8 *out;
  Bool ok = FALSE;
  if (System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
             "./a.out > " PTRCONV_RUN " 2>&1") != 0) {
    return FALSE;
  }
  out = FileRead(PTRCONV_RUN);
  if (out) {
    ok = StrCmp(out, expected) == 0;
    if (!ok) "got: %s\n", out;
    Free(out);
  }
  return ok;
}

Bool Has(U8 *out, U8 *needle)
{
  if (out && strstr(out, needle)) return TRUE;
  if (out) "missing `%s` in:\n%s\n", needle, out;
  return FALSE;
}

I32 Main()
{
  I64 correct = 0, tests = 12;
  U8 *out;

  "Test - pointer conversion warnings: ";

  out = CompileOutput(
"U0 TakeI(I64 *p) { if (p) \"take\\n\"; }
I64 *RetI(F64 *f) { return f; }
U0 Main() {
  F64 f = 1.5;
  I64 x = 3;
  I64 *q = &x;
  I64 *r = &f;
  r = &f;
  TakeI(&f);
  \"%d\\n\", RetI(&f) == &f;
  q -= q;
  \"%d %d\\n\", q == NULL, *r == *(&f)(I64 *);
}
");
  if (out) correct++;
  if (Has(out, "Incompatible types 'F64 *' is not assignable to type 'I64 *'\n --> "
               PTRCONV_FILE ":7:10")) correct++;
  if (Has(out, "Incompatible types 'F64 *' is not assignable to type 'I64 *'\n --> "
               PTRCONV_FILE ":8:5")) correct++;
  if (Has(out, "Incompatible function argument, expected 'I64 *' got 'F64 *' in function 'TakeI'\n --> "
               PTRCONV_FILE ":9:")) correct++;
  if (Has(out, "unexpected return value 'f' of type 'F64 *' expected 'I64 *'")) correct++;
  if (Has(out, "Incompatible types 'U64' is not assignable to type 'I64 *'\n --> "
               PTRCONV_FILE ":11:5")) correct++;
  if (out && !strstr(out, "error")) correct++;
  /* Still a working program */
  if (out && RunPrints("take\n1\n1 1\n")) correct++;
  Free(out);

  /* None of these warn */
  out = CompileOutput(
"class C { I64 a; };
U0 TakeU(U0 *p) { if (!p) \"null\\n\"; }
U0 Main() {
  F64 f = 1.5;
  I64 x = 3, d;
  I64 *q = &x;
  U0 *v = &f;
  I64 *z = v;
  v = q;
  q = &x;
  d = q - q;
  q += 1; q -= 1; q++; q--;
  q = (&f)(I64 *);
  q = &x;
  U8 *s = \"abc\";
  I8 *t = s;
  s = t;
  U8 **pp = &s;
  U64 *w = s + 1;
  s = w;
  Bool *b = s;
  F64 *fp = &f;
  F64 *fq = fp;
  C c; C *cp = &c;
  TakeU(&f);
  TakeU(q);
  I64 *n = NULL;
  n = 0;
  \"%d %d %d\\n\", d, *q, n == NULL;
}
");
  if (out) correct++;
  if (out && !strstr(out, "warning")) correct++;
  else if (out) "%s\n", out;
  if (out && RunPrints("0 3 1\n")) correct++;
  Free(out);

  /* `I64 *q = &f` alone, from a global */
  out = CompileOutput(
"F64 g = 2.0;
U0 Main() {
  I64 *q = &g;
  \"%d\\n\", q != NULL;
}
");
  if (Has(out, "Incompatible types 'F64 *' is not assignable to type 'I64 *'\n --> "
               PTRCONV_FILE ":3:10")) correct++;
  Free(out);

  CleanupPtrConvTest;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
