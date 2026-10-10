#include "testhelper.ktm"

/* As in C, `(` calls any expression whose type is a function pointer.
 * A cast to a function pointer type (`((i64 (*)(i64))fv)(5)`,
 * `fv(i64 (*)(i64))(5)`), a call that returns one (`GetH()(x)`) and an
 * assignment (`(fp = &F)(x)`) could not be called; variables, fields and
 * array elements could. Postfix casts did not take the nameless
 * `T (*)(params)` type at all. Arguments are checked against the
 * pointer's parameters, and `(x)(3)` on a non-function is a clear error. */

i64 Twice(i64 x) { return x * 2; }
i64 Add(i64 a, i64 b) { return a + b; }
f64 Half(f64 x) { return x / 2.0; }
f64 Mix(i64 a, f64 b, i64 c) { return a + b * c; }
i64 Sum(i64 n, ...)
{
  i64 s = n;
  for (i64 i = 0; i < argc; i++) s += argv[i];
  return s;
}

class Pt { i64 x, y; };
i64 SumPt(Pt *p) { return p->x + p->y; }

class S { i64 (*cb)(i64); i64 (*cbs[2])(i64); };

void *GetV() { return &Twice; }
auto GetH()
{
  i64 (*fp)(i64, i64) = &Add;
  return fp;
}

#define CE_SRC "408-err.ktm"
#define CE_OUT "408-err.out"

/* compile source; TRUE if it fails and prints want */
Bool FailsWith(U8 *source, U8 *want)
{
  U8 *out;
  Bool ok;
  System("rm -f " CE_SRC " " CE_OUT);
  FileWrite(CE_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  ok = System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
              "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
              "-S " CE_SRC " -o /dev/null > " CE_OUT " 2>&1") != 0;
  out = FileRead(CE_OUT);
  ok = ok && out && strstr(out, want);
  if (!ok) "got: '%s'\n", out;
  Free(out);
  System("rm -f " CE_SRC " " CE_OUT);
  return ok;
}

I32 Main()
{
  "Test - calling function pointer expressions: ";
  I64 correct = 0, tests = 0;
  void *fv = &Twice, *fadd = &Add, *fhalf = &Half, *fmix = &Mix, *fsum = &Sum;
  void *fpt = &SumPt;
  i64 (*fp)(i64);
  i64 (*tab[2])(i64);
  i64 i = 1;
  S s;
  S *sp = &s;
  Pt pt;

  /* prefix casts */
  tests++; if (((i64 (*)(i64))fv)(5) == 10) correct++;
  tests++; if (((i64 (*)(i64, i64))fadd)(5, 6) == 11) correct++;
  tests++; if (((i64 (*)(i64))GetV())(7) == 14) correct++;
  /* postfix casts, bare and in parentheses */
  tests++; if (fv(i64 (*)(i64))(3) == 6) correct++;
  tests++; if ((fv(i64 (*)(i64)))(4) == 8) correct++;
  tests++; if (((fv)(i64 (*)(i64)))(9) == 18) correct++;
  tests++; if (GetV()(i64 (*)(i64))(21) == 42) correct++;
  tests++; if (1 + fadd(i64 (*)(i64, i64))(2, 3) * 2 == 11) correct++;
  /* floats, int to float conversion, varargs, a class pointer */
  tests++; if (fhalf(f64 (*)(f64))(5.0) == 2.5) correct++;
  tests++; if (fhalf(f64 (*)(f64))(7) == 3.5) correct++;
  tests++; if (fmix(f64 (*)(i64, f64, i64))(1, 2.5, 4) == 11.0) correct++;
  tests++; if (fsum(i64 (*)(i64, ...))(100, 1, 2, 3) == 106) correct++;
  pt.x = 3;
  pt.y = 4;
  tests++; if (fpt(i64 (*)(Pt *))(&pt) == 7) correct++;
  /* the result of a call that returns a function pointer */
  tests++; if (GetH()(8, 9) == 17) correct++;
  /* the result of an assignment */
  tests++; if ((fp = &Twice)(9) == 18 && fp(1) == 2) correct++;

  /* the forms that already worked */
  tests++; if ((fp)(3) == 6 && (*fp)(4) == 8 && (&Twice)(5) == 10) correct++;
  tab[0] = &Twice;
  tab[1] = (i64 (*)(i64))fadd;
  tab[1] = &Twice;
  tests++; if (tab[i](6) == 12 && (tab[i])(7) == 14 && (*tab[0])(8) == 16) correct++;
  s.cb = &Twice;
  s.cbs[1] = &Twice;
  tests++;
  if (s.cb(1) == 2 && sp->cb(2) == 4 && (sp->cb)(3) == 6 && (*sp->cb)(4) == 8 &&
      s.cbs[1](5) == 10 && sp->cbs[i](6) == 12)
    correct++;

  /* argument checking and errors */
  tests++;
  if (FailsWith("i64 Add(i64 a, i64 b) { return a + b; }\n"
                "I32 Main() { void *f = &Add; return ((i64 (*)(i64, i64))f)(1); }\n",
                "Unexpected number of arguments 1"))
    correct++;
  tests++;
  if (FailsWith("i64 Add(i64 a, i64 b) { return a + b; }\n"
                "I32 Main() { void *f = &Add; return f(i64 (*)(i64, i64))(1, 2, 3); }\n",
                "Unexpected number of arguments 3"))
    correct++;
  tests++;
  if (FailsWith("I32 Main() { i64 x = 5; return (x)(3); }\n",
                "Cannot call integer type `x`"))
    correct++;
  /* a nameless `(*)` in a declaration is still an error */
  tests++;
  if (FailsWith("I32 Main() { i64 (*)(i64); return 0; }\n",
                "Expected function pointer name"))
    correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
