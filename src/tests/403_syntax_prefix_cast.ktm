#include "testhelper.ktm"

/* ktm syntax v0.2: C prefix casts `(T)x`. A `(` followed by a type name
 * (built-in, new or old spelling, class, union) opens a cast; its operand
 * is a unary expression, so the cast binds tighter than any binary
 * operator and `(T)p->f` casts `p->f`, as in C. A variable in scope hides
 * a type of the same name. Pointer, class pointer and function pointer
 * types (`T (*)(params)`, named or unnamed parameters) all work, and the
 * postfix cast `x(T)` keeps working. */

class Point
{
  I64 x, y;
};

union Num
{
  I64 i;
  F64 f;
};

class tm2 { I64 sec; };     /* a class whose name a variable also uses */

I64 Twice(I64 x) { return x * 2; }
I64 Add(I64 a, I64 b) { return a + b; }

I64 g_cast = (I64)2.9;      /* in a global initialiser */
U8 *g_ptr = (U8 *)0;

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - prefix casts: ";

  tests++;   /* precedence: unary level */
  I64 a = 7, b = 2;
  F64 f = 2.75;
  if ((F64)a / b == 3.5 && (F64)(a / b) == 3.0 && (I64)(F64)a / 2 == 3 &&
      (I64)-a == -7 && (I32)f * 3 == 6 && (I64)f + (I64)f == 4) correct++;
  else "\n  precedence\n";

  tests++;   /* integer widths, new and old names, bool */
  I64 big = 300;
  i8 narrow = (I8)200;      /* compared via a variable: see QUEUE narrow-cast-compare */
  if ((U8)big == 44 && (u8)big == 44 && narrow == -56 && (i16)70000 == 4464 &&
      (u32)-1 == 0xFFFFFFFF && (bool)0x800 == 1 && (Bool)0 == 0 &&
      sizeof((u8)big) == 1) correct++;
  else "\n  widths\n";

  tests++;   /* pointers and class pointers, with -> and [] after them */
  Point pt;
  pt.x = 3;
  pt.y = 4;
  U0 *vp = &pt;
  Point *pp = (Point *)vp;
  U8 *s = "hello";
  if (pp->x == 3 && ((Point *)vp)->y == 4 && (I64)pp->y == 4 &&
      *(U8 *)s == 'h' && ((u8 *)s)[1] == 'e' && *((U8 *)s + 2) == 'l') correct++;
  else "\n  pointers\n";

  tests++;   /* function pointer types */
  U0 *fv = &Twice;
  I64 (*fp)(I64) = (I64 (*)(I64))fv;
  I64 (*fp2)(I64, I64) = (I64 (*)(I64 x, I64 y))&Add;
  if (fp(21) == 42 && fp2(40, 2) == 42) correct++;
  else "\n  function pointers\n";

  tests++;   /* union, global initialisers, nested and mixed with postfix */
  Num n;
  n.f = 1.5;
  U0 *np = &n;
  if (((Num *)np)->f == 1.5 && g_cast == 2 && g_ptr == NULL &&
      (I64)a(F64) == 7 && (F64)(I64)2.7 == 2.0) correct++;
  else "\n  unions, globals, mixed\n";

  tests++;   /* parentheses around values are still parentheses */
  I64 tm2 = 5;               /* hides the class tm2 */
  I64 x = 3;
  if ((tm2) * 2 == 10 && (a + b) * 2 == 18 && (x) == 3) correct++;
  else "\n  parentheses\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
