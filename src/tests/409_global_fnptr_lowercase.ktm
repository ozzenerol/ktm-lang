#include "testhelper.ktm"

/* A global function pointer (or array of them) whose return type is a
 * lowercase built-in type or a class name, `i64 (*g)(i64);`, was taken
 * for a top-level call of `i64` and failed ("Variable or function `g` has
 * not been defined"); `I64 (*g)(I64);` worked because I64 is a keyword.
 * A type name before `(` at file scope now starts a declaration; a real
 * top-level call `Foo(...);` is still a call. */

struct Pt
{
  i64 x, y;
};

i64 Twice(i64 x) { return x * 2; }
f64 Half(f64 x) { return x / 2; }
u8 *Name() { return "ktm"; }
Pt Origin() { Pt p; p.x = 1; p.y = 2; return p; }

i64 (*g_int)(i64);
f64 (*g_flt)(f64);
u8 *(*g_str)();
bool (*g_none)(i64) = NULL;
Pt (*g_pt)();
i64 (*g_tbl[2])(i64);

i64 g_calls = 0;
void Count(i64 n) { g_calls += n; }
Count(3);                  /* a top-level call, as before */

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - global function pointers with lowercase types: ";

  tests++;
  g_int = &Twice;
  g_flt = &Half;
  g_str = &Name;
  if (g_int(21) == 42 && g_flt(5.0) == 2.5 && !StrCmp(g_str(), "ktm") && !g_none) correct++;
  else "\n  scalars\n";

  tests++;
  g_pt = &Origin;
  Pt p = g_pt();
  if (p.x == 1 && p.y == 2) correct++;
  else "\n  class return\n";

  tests++;
  g_tbl[0] = &Twice;
  g_tbl[1] = &Twice;
  if (g_tbl[0](1) + g_tbl[1](2) == 6) correct++;
  else "\n  array\n";

  tests++;
  if (g_calls == 3) correct++;
  else "\n  top-level call: %d\n", g_calls;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
