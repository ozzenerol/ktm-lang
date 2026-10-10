#include "testhelper.ktm"

/* Brace elision, as in C: an array or class element or field of an
 * initialiser list may be written without its braces, its items then
 * taken from the list in order: `L l = {1, 2, 3, 4}` is
 * `{1, {2, 3}, 4}`. A short list zero-fills the rest. ktm used to warn
 * and store the item as the whole class. Locals and globals, under the
 * JIT and compiled; too many items is still an error. */

class P { I64 x, y; };
class L { I64 t; P a; I64 z; };
union U { I64 a; F64 b; };
class C { I64 t; U u; I64 z; };
class A { I64 n; I32 v[3]; I64 e; };
class N { I64 h; L l; I64 k; };
class S { U8 name[4]; I64 n; };
class M { P a[2]; I64 z; };

L gl1 = {1, 2, 3, 4};
L gl2 = {1, 2, 3};
P gps[2] = {1, 2, 3, 4};
P gpu[] = {1, 2, 3};
A ga = {1, 2, 3, 4, 5};
N gn = {1, 2, 3, 4, 5, 6};
C gc = {1, 2, 3};
I64 gm[2][3] = {1, 2, 3, 4, 5};
S gs[2] = {"ab", 1, 'c', 'd', 'e', 'f', 2};
M gmm = {1, 2, 3, 4, 5};
L gmix = {1, {2, 3}, 4};
P gp = {7, 8};
L gwhole = {1, gp, 4};
I64 gbase = 10;
L gvals = {gbase, gbase + 1, gbase + 2, gbase + 3};

#define ELIDE_TEST_SRC "./ktm-189-elide.ktm"
#define ELIDE_TEST_OUT "./ktm-189-elide.out"
/* A single literal: adjacent strings don't concatenate inside #define */
#define ELIDE_KTM "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

/* ktm must fail with exit status 1 and say `expected` */
Bool FailsWith(U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;
  Bool ok;

  System("rm -f " ELIDE_TEST_SRC " " ELIDE_TEST_OUT);
  FileWrite(ELIDE_TEST_SRC, source, StrLen(source),
            O_CREAT | O_RDWR | O_TRUNC);
  res = System(ELIDE_KTM ELIDE_TEST_SRC " > " ELIDE_TEST_OUT " 2>&1; "
               "test $? -eq 1");
  out = FileRead(ELIDE_TEST_OUT);
  ok = res == 0 && out && strstr(out, expected);
  if (!ok) "got (%d): '%s'\n", res, out;
  Free(out);
  System("rm -f " ELIDE_TEST_SRC " " ELIDE_TEST_OUT " a.out");
  return ok;
}

#define CLS "class P { i64 x, y; }; class L { i64 t; P a; i64 z; };\n"

I32 Main()
{
  "Test - brace elision in initialisers: ";
  I64 tests = 0;
  I64 correct = 0;
  I64 k = 9;
  L l1 = {1, 2, 3, 4};
  L l2 = {1, 2, 3};
  P ps[2] = {1, 2, 3, 4};
  P pu[] = {1, 2, 3};
  A a = {1, 2, 3, 4, 5};
  N n = {1, 2, 3, 4, 5, 6};
  C c = {1, 2, 3};
  I64 m[2][3] = {1, 2, 3, 4, 5};
  S s[2] = {"ab", 1, 'c', 'd', 'e', 'f', 2};
  M mm = {1, 2, 3, 4, 5};
  L mix = {1, {2, 3}, 4};
  L vals = {k, k + 1, k + 2, k + 3};
  C cf = {1, 2.5(I64), 3};

  /* A class field */
  tests++;
  if (l1.t == 1 && l1.a.x == 2 && l1.a.y == 3 && l1.z == 4 &&
      gl1.t == 1 && gl1.a.x == 2 && gl1.a.y == 3 && gl1.z == 4) correct++;
  else "Failed - L {1, 2, 3, 4}\n";
  tests++;
  if (l2.t == 1 && l2.a.x == 2 && l2.a.y == 3 && l2.z == 0 &&
      gl2.t == 1 && gl2.a.x == 2 && gl2.a.y == 3 && gl2.z == 0) correct++;
  else "Failed - L {1, 2, 3}\n";

  /* An array of classes, sized and unsized */
  tests++;
  if (ps[0].x == 1 && ps[0].y == 2 && ps[1].x == 3 && ps[1].y == 4 &&
      gps[0].x == 1 && gps[0].y == 2 && gps[1].x == 3 && gps[1].y == 4)
    correct++;
  else "Failed - P ps[2] = {1, 2, 3, 4}\n";
  tests++;
  if (sizeof(pu) == 2 * sizeof(P) && pu[0].x == 1 && pu[0].y == 2 &&
      pu[1].x == 3 && pu[1].y == 0 &&
      sizeof(gpu) == 2 * sizeof(P) && gpu[0].x == 1 && gpu[0].y == 2 &&
      gpu[1].x == 3 && gpu[1].y == 0) correct++;
  else "Failed - P pu[] = {1, 2, 3}\n";

  /* An array field, a nested class, a union (its first member) */
  tests++;
  if (a.n == 1 && a.v[0] == 2 && a.v[1] == 3 && a.v[2] == 4 && a.e == 5 &&
      ga.n == 1 && ga.v[0] == 2 && ga.v[1] == 3 && ga.v[2] == 4 && ga.e == 5)
    correct++;
  else "Failed - array field\n";
  tests++;
  if (n.h == 1 && n.l.t == 2 && n.l.a.x == 3 && n.l.a.y == 4 &&
      n.l.z == 5 && n.k == 6 &&
      gn.h == 1 && gn.l.t == 2 && gn.l.a.x == 3 && gn.l.a.y == 4 &&
      gn.l.z == 5 && gn.k == 6) correct++;
  else "Failed - nested class\n";
  tests++;
  if (c.t == 1 && c.u.a == 2 && c.z == 3 && gc.t == 1 && gc.u.a == 2 &&
      gc.z == 3 && cf.u.a == 2 && cf.z == 3) correct++;
  else "Failed - union field\n";

  /* Arrays of arrays, char arrays (a string is the whole field), an
   * array of classes as a field */
  tests++;
  if (m[0][0] == 1 && m[0][2] == 3 && m[1][0] == 4 && m[1][1] == 5 &&
      m[1][2] == 0 && gm[0][0] == 1 && gm[0][2] == 3 && gm[1][0] == 4 &&
      gm[1][1] == 5 && gm[1][2] == 0) correct++;
  else "Failed - i64 m[2][3]\n";
  tests++;
  if (!StrCmp(s[0].name, "ab") && s[0].n == 1 && s[1].name[0] == 'c' &&
      s[1].name[3] == 'f' && s[1].n == 2 &&
      !StrCmp(gs[0].name, "ab") && gs[0].n == 1 && gs[1].name[0] == 'c' &&
      gs[1].name[3] == 'f' && gs[1].n == 2) correct++;
  else "Failed - char array field\n";
  tests++;
  if (mm.a[0].x == 1 && mm.a[0].y == 2 && mm.a[1].x == 3 &&
      mm.a[1].y == 4 && mm.z == 5 &&
      gmm.a[0].x == 1 && gmm.a[0].y == 2 && gmm.a[1].x == 3 &&
      gmm.a[1].y == 4 && gmm.z == 5) correct++;
  else "Failed - array of classes field\n";

  /* Braces still work, also mixed; a class value is still the whole
   * field; items need not be constants */
  tests++;
  if (mix.t == 1 && mix.a.x == 2 && mix.a.y == 3 && mix.z == 4 &&
      gmix.t == 1 && gmix.a.x == 2 && gmix.a.y == 3 && gmix.z == 4) correct++;
  else "Failed - braced\n";
  tests++;
  if (gwhole.t == 1 && gwhole.a.x == 7 && gwhole.a.y == 8 && gwhole.z == 4)
    correct++;
  else "Failed - class value item\n";
  tests++;
  if (vals.t == 9 && vals.a.x == 10 && vals.a.y == 11 && vals.z == 12 &&
      gvals.t == 10 && gvals.a.x == 11 && gvals.a.y == 12 && gvals.z == 13)
    correct++;
  else "Failed - non-constant items\n";

  /* Too many items are still errors */
  tests++;
  if (FailsWith(CLS "L l = {1, 2, 3, 4, 5};",
                "More initialisers than class fields for class: L")) correct++;
  else "Failed - L too many\n";
  tests++;
  if (FailsWith(CLS "void Main() { P ps[2] = {1, 2, 3, 4, 5}; } Main;",
                "expected 2 items but got 3")) correct++;
  else "Failed - P[2] too many\n";
  tests++;
  if (FailsWith("i64 m[2][2] = {1, 2, 3, 4, 5};",
                "expected 2 items but got 3")) correct++;
  else "Failed - i64[2][2] too many\n";
  tests++;
  if (FailsWith(CLS "L l = {1, 2, {3}, 4};",
                "Cannot use an initialiser list for field y of type i64"))
    correct++;
  else "Failed - braces for a scalar field\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
