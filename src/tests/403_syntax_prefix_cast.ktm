#include "testhelper.ktm"

/* ktm syntax v0.2: C prefix casts `(T)x`. A `(` followed by a type name
 * (built-in, new or old spelling, class, union) opens a cast; its operand
 * is a unary expression, so the cast binds tighter than any binary
 * operator and `(T)p->f` casts `p->f`, as in C. A variable in scope hides
 * a type of the same name. Pointer, class pointer and function pointer
 * types (`T (*)(params)`, named or unnamed parameters) all work, and the
 * postfix cast `x(T)` keeps working. */

struct Point {
    i64 x, y;
};

union Num {
    i64 i;
    f64 f;
};

struct tm2 { i64 sec; };     /* a class whose name a variable also uses */

i64 twice(i64 x) { return x * 2; }
i64 add(i64 a, i64 b) { return a + b; }

i64 gCast = (i64)2.9;      /* in a global initialiser */
u8 *gPtr = (u8 *)0;

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - prefix casts: ");

    tests++;   /* precedence: unary level */
    i64 a = 7, b = 2;
    f64 f = 2.75;
    if ((f64)a / b == 3.5 && (f64)(a / b) == 3.0 && (i64)(f64)a / 2 == 3 &&
            (i64)-a == -7 && (i32)f * 3 == 6 && (i64)f + (i64)f == 4) { correct++; }
    else { printf("\n  precedence\n"); }

    tests++;   /* integer widths, new and old names, bool */
    i64 big = 300;
    i8 narrow = (i8)200;      /* compared via a variable: see QUEUE narrow-cast-compare */
    if ((u8)big == 44 && (u8)big == 44 && narrow == -56 && (i16)70000 == 4464 &&
            (u32)-1 == 0xFFFFFFFF && (bool)0x800 == 1 && (bool)0 == 0 &&
            sizeof((u8)big) == 1) { correct++; }
    else { printf("\n  widths\n"); }

    tests++;   /* pointers and class pointers, with -> and [] after them */
    Point pt;
    pt.x = 3;
    pt.y = 4;
    void *vp = &pt;
    Point *pp = (Point *)vp;
    u8 *s = "hello";
    if (pp.x == 3 && ((Point *)vp).y == 4 && (i64)pp.y == 4 &&
            *(u8 *)s == 'h' && ((u8 *)s)[1] == 'e' && *((u8 *)s + 2) == 'l') { correct++; }
    else { printf("\n  pointers\n"); }

    tests++;   /* function pointer types */
    void *fv = &twice;
    fn(i64) i64 fp = (fn(i64) i64)fv;
    fn(i64, i64) i64 fp2 = (fn(i64 x, i64 y) i64)&add;
    if (fp(21) == 42 && fp2(40, 2) == 42) { correct++; }
    else { printf("\n  function pointers\n"); }

    tests++;   /* union, global initialisers, nested and mixed with postfix */
    Num n;
    n.f = 1.5;
    void *np = &n;
    if (((Num *)np).f == 1.5 && gCast == 2 && gPtr == null &&
            (i64)(f64)a == 7 && (f64)(i64)2.7 == 2.0) { correct++; }
    else { printf("\n  unions, globals, mixed\n"); }

    tests++;   /* parentheses around values are still parentheses */
    i64 tm2 = 5;               /* hides the class tm2 */
    i64 x = 3;
    if ((tm2) * 2 == 10 && (a + b) * 2 == 18 && (x) == 3) { correct++; }
    else { printf("\n  parentheses\n"); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
