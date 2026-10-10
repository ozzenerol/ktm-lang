#include "testhelper.ktm"

/* A class field can hold a class by value only once that class is
 * complete. Outside modules, `class B; class A { B b; i64 x; };` with B
 * defined later compiled silently with B taken as 0 bytes, so
 * sizeof(A) was 8 and `x` shared its bytes with `b`; `class A { A a; }`
 * gave a size of 0. Now both are the error modules already gave, for
 * classes, unions and arrays of them. A pointer to an incomplete class,
 * or an array of such pointers, is fine. */

#define AT_SRC "./ktm-431-incomplete.ktm"
#define AT_OUT "./ktm-431-incomplete.out"

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

/* ktm prints no warnings, `errors` errors and want (if any) for body */
Bool Prints(U8 *body, I64 errors, U8 *want=NULL)
{
  U8 *src = MStrPrint("%s\nI32 Main() { return 0; }\n", body);
  U8 *out = Diagnose(src);
  Bool ok = out && Count(out, "warning:") == 0 &&
            Count(out, "error:") == errors && (!want || strstr(out, want));
  if (!ok) "\n%s\n----\n%s\n", body, out;
  Free(out);
  Free(src);
  return ok;
}

/* runtime: a pointer to a class completed later, and a field of a
 * class completed before, get the right layout */
class Later;
class Holder { Later *p; i64 x; };
class Later { i64 y, z; };
class Outer { Later l; i64 x; };

I32 Main()
{
  "Test - a field can't hold an incomplete class by value: ";
  I64 correct = 0, tests = 0;

  /* pointers, arrays of pointers, complete classes, empty classes and
   * function pointer types naming an incomplete class: no diagnostic */
  tests++;
  if (Prints(
"class B;
union U;
class A { B *b; B **pp; B *arr[3]; U *u; fn(B *) i64 f; i64 x; };
class B { i64 y, z; };
class C { B b; B arr[2]; i64 x; };
class E {};
class F { E e; i64 x; };
union V { B b; i64 x; };", 0)) correct++;

  /* the case from the report */
  tests++;
  if (Prints("class B;\nclass A { B b; i64 x; };\nclass B { i64 y, z; };", 1,
             "field `b` of A holds a B, which is not complete here: use a pointer"))
    correct++;

  /* never completed, an array of it, a union, inside a union */
  tests++;
  if (Prints("class B;\nclass A { i64 x; B b; };", 1,
             "field `b` of A holds a B, which is not complete here"))
    correct++;
  tests++;
  if (Prints("class B;\nclass A { B arr[2]; };\nclass B { i64 y; };", 1,
             "field `arr` of A holds a B, which is not complete here"))
    correct++;
  tests++;
  if (Prints("union U;\nclass A { U u; };\nunion U { i64 y; f64 z; };", 1,
             "field `u` of A holds a U, which is not complete here"))
    correct++;
  tests++;
  if (Prints("class B;\nunion W { B b; i64 x; };\nclass B { i64 y; };", 1,
             "field `b` of W holds a B, which is not complete here"))
    correct++;

  /* a class holding itself by value */
  tests++;
  if (Prints("class A { i64 x; A a; };", 1,
             "field `a` of A holds a A, which is not complete here"))
    correct++;

  /* the layouts that are still allowed */
  Later l;
  Holder h;
  h.p = &l;
  h.x = 7;
  l.y = 1;
  l.z = 2;
  tests++; if (sizeof(Holder) == 16 && h.p->z == 2 && h.x == 7) correct++;
  Outer o;
  tests++; if (sizeof(Outer) == 24 && &o.x(U8 *) - &o(U8 *) == 16) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
