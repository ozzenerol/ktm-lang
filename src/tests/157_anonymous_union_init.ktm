#include "testhelper.ktm"

/* A union takes one positional initialiser item, for its first member,
 * as in C. An anonymous union inside a class took one item per member:
 * `W w = {1,2,3}` set t=1, i=2, then f=3.0 over i and left z unset.
 * Union variables and union-typed fields could not take a brace list
 * at all. */

#define UNION_INIT_FILE "__157_anonymous_union_init.ktm"
#define UNION_INIT_OUT  "__157_anonymous_union_init.out"

class W157 { U8 t; union { I32 i; F32 f; }; I64 z; };
class D157 : W157 { I64 e; };
union U157 { I32 i; F32 f; };
class X157 { U8 t; U157 u; I64 z; };
class P157 { I64 x, y; };
union V157 { P157 p; I64 q; };
/* The union's first member is an anonymous class: its fields take the
 * items. A later anonymous class takes none. */
class N157 { I64 a; union { class { I32 lo, hi; }; I64 q; }; I64 z; };
class M157 { I64 a; union { I64 q; class { I32 lo, hi; }; }; I64 z; };
/* A union in a union. */
class UU157 { I64 a; union { union { I16 s; I64 l; }; I32 m; }; I64 z; };
/* The second member is larger than the first: the rest is zero. */
class B157 { I64 a; union { I32 s; I64 big; }; I64 z; };
class Fl157 { union { F32 f; I32 i; }; I64 z; };

W157 g_157_w = {1,2,3};
D157 g_157_d = {1,2,3,4};
U157 g_157_u = {5};
X157 g_157_x = {1,{2},3};
V157 g_157_v = {{7,8}};
N157 g_157_n = {1,2,3,4};
M157 g_157_m = {1,2,3};
UU157 g_157_uu = {1,-2,3};
B157 g_157_b = {1,-1,3};
Fl157 g_157_fl = {2.5,9};
W157 g_157_wa[2] = {{1,2,3},{4,5,6}};

Bool TestGlobals(U0)
{
  Bool ok = TRUE;
  if (g_157_w.t != 1 || g_157_w.i != 2 || g_157_w.z != 3) ok = FALSE;
  if (g_157_d.t != 1 || g_157_d.i != 2 || g_157_d.z != 3 || g_157_d.e != 4) ok = FALSE;
  if (g_157_u.i != 5) ok = FALSE;
  if (g_157_x.t != 1 || g_157_x.u.i != 2 || g_157_x.z != 3) ok = FALSE;
  if (g_157_v.p.x != 7 || g_157_v.p.y != 8) ok = FALSE;
  if (g_157_n.a != 1 || g_157_n.lo != 2 || g_157_n.hi != 3 || g_157_n.z != 4) ok = FALSE;
  if (g_157_m.a != 1 || g_157_m.q != 2 || g_157_m.z != 3) ok = FALSE;
  if (g_157_uu.a != 1 || g_157_uu.l != 0xFFFE || g_157_uu.z != 3) ok = FALSE;
  if (g_157_b.a != 1 || g_157_b.big != 0xFFFFFFFF || g_157_b.z != 3) ok = FALSE;
  if (g_157_fl.f != 2.5 || g_157_fl.z != 9) ok = FALSE;
  if (g_157_wa[0].i != 2 || g_157_wa[0].z != 3) ok = FALSE;
  if (g_157_wa[1].t != 4 || g_157_wa[1].i != 5 || g_157_wa[1].z != 6) ok = FALSE;
  if (sizeof(W157) != 16 || sizeof(B157) != 24) ok = FALSE;
  return ok;
}

/* Leave non-zero bytes on the stack for the next call's locals. */
I64 Dirty(U0)
{
  I64 junk[32];
  I64 k, sum = 0;
  for (k = 0; k < 32; k++) junk[k] = -1;
  for (k = 0; k < 32; k++) sum += junk[k];
  return sum;
}

Bool CheckLocals(U0)
{
  W157 w = {1,2,3};
  D157 d = {1,2,3,4};
  U157 u = {5};
  X157 x = {1,{2},3};
  V157 v = {{7,8}};
  N157 n = {1,2,3,4};
  M157 m = {1,2,3};
  UU157 uu = {1,-2,3};
  B157 b = {1,-1,3};
  Fl157 fl = {2.5,9};
  W157 wa[2] = {{1,2,3},{4,5,6}};
  Bool ok = TRUE;
  if (w.t != 1 || w.i != 2 || w.z != 3) ok = FALSE;
  if (d.t != 1 || d.i != 2 || d.z != 3 || d.e != 4) ok = FALSE;
  if (u.i != 5) ok = FALSE;
  if (x.t != 1 || x.u.i != 2 || x.z != 3) ok = FALSE;
  if (v.p.x != 7 || v.p.y != 8) ok = FALSE;
  if (n.a != 1 || n.lo != 2 || n.hi != 3 || n.z != 4) ok = FALSE;
  if (m.a != 1 || m.q != 2 || m.z != 3) ok = FALSE;
  if (uu.a != 1 || uu.l != 0xFFFE || uu.z != 3) ok = FALSE;
  if (b.a != 1 || b.big != 0xFFFFFFFF || b.z != 3) ok = FALSE;
  if (fl.f != 2.5 || fl.z != 9) ok = FALSE;
  if (wa[0].i != 2 || wa[0].z != 3) ok = FALSE;
  if (wa[1].t != 4 || wa[1].i != 5 || wa[1].z != 6) ok = FALSE;
  return ok;
}

Bool TestLocals(U0)
{
  Dirty;
  return CheckLocals;
}

U0 CleanupUnionInitTest(U0)
{
  Rm(UNION_INIT_FILE);
  Rm(UNION_INIT_OUT);
  Rm("./a.out");
}

/* `source` must fail to compile with `msg` at `loc`. */
Bool CompileFailsWith(U8 *source, U8 *msg, U8 *loc)
{
  U8 *out, *first;
  I64 res;
  Bool ok;

  CleanupUnionInitTest;
  if (!FileWrite(UNION_INIT_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  res = System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " UNION_INIT_FILE " > " UNION_INIT_OUT " 2>&1");
  out = FileRead(UNION_INIT_OUT);
  if (!out) {
    CleanupUnionInitTest;
    return FALSE;
  }
  ok = res == 1;
  first = strstr(out, "error: ");
  if (!first || StrNCmp(first + 7, msg, StrLen(msg))) ok = FALSE;
  else if (!strstr(first, loc)) ok = FALSE;
  if (!ok) "status %d\n%s\n", res, out;
  Free(out);
  CleanupUnionInitTest;
  return ok;
}

Bool TestTooManyForClass(U0)
{
  return CompileFailsWith(
"class W { U8 t; union { I32 i; F32 f; }; I64 z; };
U0 Main() { W w = {1,2,3,4}; }
", "More initialisers than class fields for class: W", "2:27");
}

Bool TestTooManyForUnion(U0)
{
  return CompileFailsWith(
"union U { I32 i; F32 f; };
U u = {1,2};
U0 Main() {}
", "More initialisers than class fields for class: U", "2:11");
}

I32 Main()
{
  "Test - Anonymous union initialiser: ";
  I64 tests = 4;
  I64 correct = 0;

  if (TestGlobals()) correct++;
  else "Failed - TestGlobals()\n";

  if (TestLocals()) correct++;
  else "Failed - TestLocals()\n";

  if (TestTooManyForClass()) correct++;
  else "Failed - TestTooManyForClass()\n";

  if (TestTooManyForUnion()) correct++;
  else "Failed - TestTooManyForUnion()\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
