#include "testhelper.ktm"

/* `inline` on an ordinary ktm function definition used to turn every
 * call into a statement block that the IR could not lower ("Expr
 * Unhandled AST kind: AST_COMPOUND_STMT"). `inline` is only a hint, so
 * these must behave exactly like normal functions. */

class Pt
{
  I64 x, y, z;
};

I64 g_hits = 0;

inline I64 Twice(I64 x) { return x * 2; }
inline I64 Fact(I64 n) { if (n <= 1) return 1; return n * Fact(n - 1); }
inline I64 Quad(I64 x) { return Twice(Twice(x)); }
inline I64 AddDef(I64 a, I64 b = 10) { return a + b; }
public inline I64 Pub(I64 x) { return x + 1; }
inline U0 Bump(I64 n) { g_hits += n; }
inline U0 Tick() { g_hits++; }
inline Pt MakePt(I64 v) { Pt p; p.x = v; p.y = v * 2; p.z = v * 3; return p; }
inline F64 Half(F64 f) { return f / 2.0; }
inline I64 Proto(I64 x);
inline I64 Proto(I64 x) { return x - 1; }

I32 Main()
{
  I64 correct = 0, tests = 12;

  "Test - inline functions: ";

  if (Twice(21) == 42) correct++;
  I64 v = Twice(5) + Twice(6);
  if (v == 22) correct++;
  if (Fact(5) == 120) correct++;
  if (Quad(3) == 12) correct++;

  I64 (*fp)(I64 a) = &Twice;
  if (fp(8) == 16) correct++;

  if (AddDef(1) == 11 && AddDef(1, 2) == 3) correct++;
  if (Pub(41) == 42) correct++;

  Bump(5);
  Tick;
  Tick();
  if (g_hits == 7) correct++;

  Pt p = MakePt(4);
  if (p.x == 4 && p.y == 8 && p.z == 12) correct++;

  if (Half(5.0) == 2.5) correct++;
  if (Proto(10) == 9) correct++;
  if (Twice(Proto(Fact(3))) == 10) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
