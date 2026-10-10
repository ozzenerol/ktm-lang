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
"void TakeI(i64 *p) { if (p) \"take\\n\"; }
i64 *RetI(f64 *f) { return f; }
void Main() {
  f64 f = 1.5;
  i64 x = 3;
  i64 *q = &x;
  i64 *r = &f;
  r = &f;
  TakeI(&f);
  \"%d\\n\", RetI(&f) == &f;
  q -= q;
  \"%d %d\\n\", q == NULL, *r == *(&f)(i64 *);
}
");
  if (out) correct++;
  if (Has(out, "Incompatible types 'f64 *' is not assignable to type 'i64 *'\n --> "
               PTRCONV_FILE ":7:10")) correct++;
  if (Has(out, "Incompatible types 'f64 *' is not assignable to type 'i64 *'\n --> "
               PTRCONV_FILE ":8:5")) correct++;
  if (Has(out, "Incompatible function argument, expected 'i64 *' got 'f64 *' in function 'TakeI'\n --> "
               PTRCONV_FILE ":9:")) correct++;
  if (Has(out, "unexpected return value 'f' of type 'f64 *' expected 'i64 *'")) correct++;
  if (Has(out, "Incompatible types 'u64' is not assignable to type 'i64 *'\n --> "
               PTRCONV_FILE ":11:5")) correct++;
  if (out && !strstr(out, "error")) correct++;
  /* Still a working program */
  if (out && RunPrints("take\n1\n1 1\n")) correct++;
  Free(out);

  /* None of these warn */
  out = CompileOutput(
"class C { i64 a; };
void TakeU(void *p) { if (!p) \"null\\n\"; }
void Main() {
  f64 f = 1.5;
  i64 x = 3, d;
  i64 *q = &x;
  void *v = &f;
  i64 *z = v;
  v = q;
  q = &x;
  d = q - q;
  q += 1; q -= 1; q++; q--;
  q = (&f)(i64 *);
  q = &x;
  u8 *s = \"abc\";
  i8 *t = s;
  s = t;
  u8 **pp = &s;
  u64 *w = s + 1;
  s = w;
  bool *b = s;
  f64 *fp = &f;
  f64 *fq = fp;
  C c; C *cp = &c;
  TakeU(&f);
  TakeU(q);
  i64 *n = NULL;
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
"f64 g = 2.0;
void Main() {
  i64 *q = &g;
  \"%d\\n\", q != NULL;
}
");
  if (Has(out, "Incompatible types 'f64 *' is not assignable to type 'i64 *'\n --> "
               PTRCONV_FILE ":3:10")) correct++;
  Free(out);

  CleanupPtrConvTest;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
