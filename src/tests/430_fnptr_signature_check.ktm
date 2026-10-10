#include "testhelper.ktm"

/* A function pointer only takes a function (or function pointer) with
 * the same signature. Before, `i64 (*op)(u8 *) = &t;` with a `t` taking
 * an i64 compiled silently, as did passing or returning one, so the call
 * through it got the wrong arguments. Now that is an error naming both
 * types, the same in an assignment, an initialiser (local, global, class
 * field, array element), an argument, a return and a conditional, in the
 * old declarator form and the fn form. void * converts both ways and
 * null converts to any function pointer, as before; `...` and `T ...name`
 * must match exactly. */

#define AT_SRC "./ktm-430-fnsig.ktm"
#define AT_OUT "./ktm-430-fnsig.out"

U8 *Diagnose(U8 *source)
{
  System("rm -f " AT_SRC " " AT_OUT);
  FileWrite(AT_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " AT_SRC " -o /dev/null > " AT_OUT " 2>&1");
  U8 *out = FileRead(AT_OUT);
  System("rm -f " AT_SRC " " AT_OUT);
  return out;
}

I64 Count(U8 *s, U8 *what)
{
  I64 n = 0;
  while (s && (s = strstr(s, what))) {
    n++;
    s++;
  }
  return n;
}

/* ktm prints `errors` errors and want (if any) for body, and no
 * warnings when there is no error (after a `return` that is an error,
 * the function is also said to maybe not return a value) */
Bool Prints(U8 *body, I64 errors, U8 *want=NULL)
{
  U8 *src = MStrPrint(
"i64 t(i64 x) { return x; }
i64 s(u8 *p) { return 0; }
i64 v(i64 n, ...) { return n; }
i64 nv(i64 n, i64 ...xs) { return n; }
u8 r8(i64 x) { return 0; }
i64 take(fn(i64) i64 f) { return f(1); }
i64 takeold(i64 (*f)(u8 *)) { return 0; }
i64 takev(void *p) { return 0; }
class S { fn(u8 *) i64 cb; };
%s
", body);
  U8 *out = Diagnose(src);
  Bool ok = out && (errors || Count(out, "warning:") == 0) &&
            Count(out, "error:") == errors && (!want || strstr(out, want));
  if (!ok) "\n%s\n----\n%s\n", body, out;
  Free(out);
  Free(src);
  return ok;
}

#define WRONG "cannot convert fn(i64) i64 to fn(u8*) i64"

/* runtime: what is still allowed calls through correctly */
i64 Add1(i64 x) { return x + 1; }
i64 Apply(fn(i64) i64 f, i64 x) { return f(x); }
fn(i64) i64 Pick() { return &Add1; }
class Ops { fn(i64) i64 op; };

I32 Main()
{
  "Test - function pointer signatures are checked: ";
  I64 correct = 0, tests = 0;

  /* the same signature, void * both ways, null: no diagnostic */
  tests++;
  if (Prints(
"i64 (*g1)(i64) = &t;
fn(u8 *) i64 g2 = &s;
fn(i64) i64 g3[2] = { &t, &t };
fn(i64) i64 r() { return &t; }
void f()
{
  fn(i64) i64 a = &t;
  i64 (*b)(u8 *) = &s;
  a = &t; b = &s; a = g1; b = g2;
  take(&t); take(a); take(null); takeold(&s); takev(&t);
  S x = { &s };
  x.cb = &s; x.cb = b; x.cb = null;
  fn(u8 *) i64 arr[2] = { &s, b };
  fn(u8 *) i64 c = 1 ? &s : b;
  c = 1 ? &s : null;
  void *p = &t; a = p; p = a;
  fn(i64, ...) i64 va = &v;
  fn(i64, i64 ...xs) i64 vn = &nv;
  fn(i64) i64 *pa = &a;
  fn(i64) i64 *pb = pa;
}", 0)) correct++;

  /* initialisers: global and local, old and fn form */
  tests++; if (Prints("i64 (*op)(u8 *) = &t;", 1, WRONG)) correct++;
  tests++; if (Prints("fn(u8 *) i64 op = &t;", 1, WRONG)) correct++;
  tests++; if (Prints("void f() { i64 (*a)(u8 *) = &t; }", 1, WRONG)) correct++;
  tests++; if (Prints("void f() { fn(u8 *) i64 a = &t; }", 1, WRONG)) correct++;

  /* assignment, from a function and from a function pointer */
  tests++; if (Prints("void f() { fn(u8 *) i64 a = &s; a = &t; }", 1, WRONG)) correct++;
  tests++;
  if (Prints("void f() { fn(u8 *) i64 a = &s; fn(i64) i64 b = &t; a = b; }", 1, WRONG))
    correct++;

  /* argument */
  tests++;
  if (Prints("void f() { take(&s); }", 1, "cannot convert fn(u8*) i64 to fn(i64) i64"))
    correct++;
  tests++; if (Prints("void f() { takeold(&t); }", 1, WRONG)) correct++;

  /* return */
  tests++; if (Prints("fn(u8 *) i64 r() { return &t; }", 1, WRONG)) correct++;

  /* class field: initialiser and assignment */
  tests++; if (Prints("void f() { S x = { &t }; }", 1, WRONG)) correct++;
  tests++; if (Prints("void f() { S x; x.cb = &t; }", 1, WRONG)) correct++;

  /* array initialiser, local and global */
  tests++; if (Prints("void f() { fn(u8 *) i64 arr[2] = { &s, &t }; }", 1, WRONG)) correct++;
  tests++; if (Prints("fn(u8 *) i64 garr[2] = { &s, &t };", 1, WRONG)) correct++;

  /* conditional: the branches must agree */
  tests++;
  if (Prints("void f() { fn(u8 *) i64 c = 1 ? &t : &s; }", 1,
             "cannot convert fn(u8*) i64 to fn(i64) i64"))
    correct++;

  /* varargs, named varargs and the return type must match exactly */
  tests++;
  if (Prints("void f() { fn(i64, ...) i64 a = &t; }", 1,
             "cannot convert fn(i64) i64 to fn(i64, ...) i64"))
    correct++;
  tests++;
  if (Prints("void f() { fn(i64) i64 a = &v; }", 1,
             "cannot convert fn(i64, ...) i64 to fn(i64) i64"))
    correct++;
  tests++;
  if (Prints("void f() { fn(i64, ...) i64 a = &nv; }", 1,
             "cannot convert fn(i64, i64 ...) i64 to fn(i64, ...) i64"))
    correct++;
  tests++;
  if (Prints("void f() { fn(i64) i64 a = &r8; }", 1,
             "cannot convert fn(i64) u8 to fn(i64) i64"))
    correct++;

  /* a pointer to a function pointer: the signatures still must agree */
  tests++;
  if (Prints("void f() { fn(i64) i64 a = &t; fn(u8 *) i64 *pb = &a; }", 1,
             "cannot convert fn(i64) i64 * to fn(u8*) i64 *"))
    correct++;

  /* the allowed conversions call through correctly */
  Ops o = { &Add1 };
  void *vp = &Add1;
  fn(i64) i64 back = vp;
  tests++; if (Apply(&Add1, 1) == 2) correct++;
  tests++; if (Pick()(2) == 3) correct++;
  tests++; if (o.op(3) == 4) correct++;
  tests++; if (back(4) == 5) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
