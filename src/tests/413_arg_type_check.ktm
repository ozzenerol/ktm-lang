#include "testhelper.ktm"

/* Call arguments are checked against the parameter types like C does.
 * Before, any pointer passed as an 8-byte integer, any class as any other
 * class and a class as a number, silently: `Twice("str")` compiled. Now
 * pointer/integer mixes and pointers to incompatible types warn (C's
 * -Wint-conversion / -Wincompatible-pointer-types), a class where another
 * class or a scalar is expected (or a scalar or a pointer where a float or
 * a class is) is an error, and numbers convert silently, 0 and null pass
 * as any pointer, void * converts both ways, a derived class pointer is a
 * base class pointer, and varargs and default arguments work as before.
 * Direct calls, function pointer calls and externs are all checked. */

#define AT_SRC "./ktm-413-args.ktm"
#define AT_OUT "./ktm-413-args.out"

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

/* ktm prints `warnings` warnings, `errors` errors and want (if any) */
Bool Prints(U8 *body, I64 warnings, I64 errors, U8 *want=NULL)
{
  U8 *src = MStrPrint(
"i64 Twice(i64 x) { return x * 2; }
i64 TakeP(i64 *p) { return 0; }
i64 TakeV(void *p) { return 0; }
i64 TakeU8(u8 *p) { return 0; }
f64 TakeF(f64 x) { return x; }
bool TakeB(bool b) { return b; }
class A { i64 x; };
class B { i64 x; };
class D : A { i64 y; };
class G : D { i64 z; };
i64 TakeA(A a) { return a.x; }
i64 TakeAp(A *a) { return 0; }
i64 TakeFn(i64 (*f)(i64)) { return 0; }
i64 Dflt(i64 *p = null, i64 n = 3) { return n; }
i64 Va(i64 n, ...) { return argc; }
extern \"c\" u64 strlen(u8 *s);
class S { i64 (*cb)(i64 *); };
void Main()
{
  i64 n = 1, arr[4];
  i64 *ip = &n;
  i32 *i32p = null;
  u8 *bp = \"x\";
  f64 f = 1.5;
  void *vp = ip;
  A a;
  B b;
  D d;
  G g;
  S s;
  i64 (*fp)(i64 *) = &TakeP;
%s
}
", body);
  U8 *out = Diagnose(src);
  Bool ok = out && Count(out, "warning:") == warnings &&
            Count(out, "error:") == errors && (!want || strstr(out, want));
  if (!ok) "\n%s\n----\n%s\n", body, out;
  Free(out);
  Free(src);
  return ok;
}

/* runtime: implicitly converted arguments arrive converted */
f64 Halve(f64 x) { return x / 2.0; }
i64 Trunc(i64 x) { return x; }
i64 Wide(i64 x) { return x; }
class Base { i64 v; };
class Derived : Base { i64 w; };
i64 BaseV(Base *b) { return b->v; }

I32 Main()
{
  "Test - argument type checking: ";
  I64 correct = 0, tests = 0;
  u8 small = 200;
  Derived dv;

  /* silent: numbers, 0/null, void *, strings, arrays, Bool, derived
   * classes, the same class by value, varargs, defaults, functions */
  tests++;
  if (Prints(
"  Twice(f); Twice(n); TakeF(n); TakeF(f); Twice('a');
  TakeP(0); TakeP(null); TakeP(vp); TakeP(arr); TakeV(ip); TakeV(bp); TakeV(&Twice);
  TakeU8(\"lit\"); TakeU8(bp); TakeB(ip); TakeB(n); TakeB(&Twice);
  TakeAp(&a); TakeAp(&d); TakeAp(&g); TakeA(a);
  Va(1, ip, f, \"s\", a); printf(\"%d %s %f\\n\", n, bp, f);
  Dflt(); Dflt(ip); Dflt(, 5); Dflt(null, 2);
  TakeFn(&Twice); TakeFn(vp); TakeFn(null); strlen(bp);
  s.cb = &TakeP; s.cb(ip); fp(ip); fp(null);", 0, 0)) correct++;

  /* a pointer where an integer is expected, and the other way round */
  tests++;
  if (Prints("  Twice(\"str\");", 1, 0,
             "expected 'i64' got 'u8[4]' in function 'Twice': makes an integer from a pointer without a cast"))
    correct++;
  tests++;
  if (Prints("  Twice(ip); TakeP(n); Dflt(5);", 3, 0,
             "expected 'i64 *' got 'i64' in function 'TakeP': makes a pointer from an integer without a cast"))
    correct++;

  /* pointers to incompatible types (byte pointers convert to any number
   * pointer, as in assignments) */
  tests++;
  if (Prints("  TakeP(&f); TakeP(i32p); TakeAp(&b); TakeFn(ip); TakeP(bp); TakeU8(ip);", 4, 0,
             "expected 'A *' got 'B *' in function 'TakeAp'"))
    correct++;
  tests++;
  if (Prints("  TakeP(&f);", 1, 0, "expected 'i64 *' got 'f64 *' in function 'TakeP'"))
    correct++;

  /* function pointer calls and externs are checked too */
  tests++;
  if (Prints("  fp(n); s.cb(&f); strlen(n);", 3, 0,
             "expected 'u8 *' got 'i64' in function 'strlen'"))
    correct++;

  /* a class for another class or a scalar, a scalar or pointer for a
   * class, a pointer for a float: errors */
  tests++;
  if (Prints("  TakeA(b);", 0, 1, "error: Incompatible function argument, expected 'A' got 'B' in function 'TakeA'"))
    correct++;
  tests++;
  if (Prints("  Twice(a);", 0, 1, "expected 'i64' got 'A' in function 'Twice'")) correct++;
  tests++;
  if (Prints("  TakeA(n);", 0, 1, "expected 'A' got 'i64' in function 'TakeA'")) correct++;
  tests++;
  if (Prints("  TakeA(d);", 0, 1, "expected 'A' got 'D' in function 'TakeA'")) correct++;
  tests++;
  if (Prints("  TakeF(ip);", 0, 1, "expected 'f64' got 'i64 *' in function 'TakeF'")) correct++;

  /* the conversions themselves */
  tests++; if (Halve(5) == 2.5) correct++;
  tests++; if (Trunc(7.9) == 7) correct++;
  tests++; if (Wide(small) == 200) correct++;
  dv.v = 41;
  dv.w = 1;
  tests++; if (BaseV(&dv) == 41) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
