#include "testhelper.ktm"

/* ktm syntax v0.2: `struct` is the same as `class` and `pub` the same as
 * `public`. struct in every place class works: definitions, inheritance,
 * forward declarations, nested and anonymous members, pointers to itself;
 * pub on globals, functions and extern "c" declarations, and on the
 * functions of a -lib library, which must be exported as with public. */

struct Point
{
  I64 x, y;
};

pub struct Size
{
  I64 w, h;
};

struct Box : Point     /* inherits x and y */
{
  Size size;
};

struct Node;           /* forward declaration */
struct Node
{
  I64 v;
  Node *next;
};

struct Holder
{
  struct { I64 a, b; } pair;   /* nested, anonymous struct type */
  struct Inner { I64 c; } in;  /* nested, named */
  union { I64 i; F64 f; } num;
};

pub I64 g_scale = 3;
pub I64 Area(Size *s) { return s->w * s->h * g_scale / 3; }
pub extern "c" I32 abs(I32 x);
class Mixed { Point p; };      /* class and struct can be mixed */

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - struct and pub: ";

  tests++;
  Box b;
  b.x = 1;
  b.y = 2;
  b.size.w = 3;
  b.size.h = 4;
  if (b.x + b.y == 3 && Area(&b.size) == 12 && sizeof(Box) == 32 &&
      !StrCmp(typeof(b), "Box")) correct++;
  else "\n  struct with inheritance\n";

  tests++;
  Node n1, n2;
  n1.v = 1;
  n1.next = &n2;
  n2.v = 2;
  n2.next = NULL;
  if (n1.next->v == 2 && !n2.next) correct++;
  else "\n  self-referencing struct\n";

  tests++;
  Holder h;
  h.pair.a = 5;
  h.pair.b = 6;
  h.in.c = 7;
  h.num.f = 1.5;
  if (h.pair.a + h.pair.b + h.in.c == 18 && h.num.f == 1.5) correct++;
  else "\n  nested structs and a union\n";

  tests++;
  Mixed m;
  m.p.x = 9;
  Point *pp = &m.p;
  if (pp->x == 9 && abs(-4) == 4 && g_scale == 3) correct++;
  else "\n  pub globals, functions and externs\n";

  tests++;   /* a library whose functions are pub is linked like one with public */
  I64 ok = 0;
  if (System("D=${KTM_INSTALL_DIR:-/usr/local} && H=$PWD/../../ktm && P=$PWD && "
             "mkdir -p ./ktm-402-prefix/include ./ktm-402-prefix/lib ./ktm-402-work && "
             "cp $D/include/std.ktmh ./ktm-402-prefix/include/ && "
             "cp -P $D/lib/libktm.* ./ktm-402-prefix/lib/ && "
             "printf 'pub struct Pair { I64 a, b; };\npub I64 Ktm402Sum(Pair *p) { return p->a + p->b; }\n'"
             " > ./ktm-402-work/ktm402.ktm && "
             "cd ./ktm-402-work && "
             "$H -lib ktm402 --install-dir=$P/ktm-402-prefix ktm402.ktm > /dev/null 2>&1") == 0) ok++;
  System("printf '#link <ktm402>\n"
         "struct Pair { I64 a, b; };\n"
         "I64 Ktm402Sum(Pair *p);\n"
         "I32 Main() { Pair p; p.a = 40; p.b = 2; if (Ktm402Sum(&p) == 42) return 0; return 1; }\n'"
         " > ./ktm-402-use.ktm");
  if (System("../../ktm -jit --install-dir=./ktm-402-prefix ./ktm-402-use.ktm") == 0) ok++;
  if (System("../../ktm --install-dir=./ktm-402-prefix ./ktm-402-use.ktm -o ./ktm-402-use && "
             "./ktm-402-use") == 0) ok++;
  System("rm -rf ./ktm-402-prefix ./ktm-402-work ./ktm-402-use.ktm ./ktm-402-use");
  if (ok == 3) correct++;
  else "\n  pub in a -lib library (%d/3)\n", ok;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
