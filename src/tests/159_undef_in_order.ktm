#include "testhelper.ktm"

/* The parser reads tokens from a 16-token ring that the lexer fills
 * ahead of it, and macros were expanded only as the parser took each
 * token: by then a later `#undef` or `#define` of the same name had
 * already run, so a use just before it saw the new value (or none). */

#define N 3
I64 g_a = N;
#undef N
#define N 4
I64 g_b = N;

#define S "one"
U8 *g_s1 = S;
#undef S
#define S "two"
U8 *g_s2 = S;

#define V 10
I64 g_c = V;
#undef V
I64 V = 7;

#define K (2 * 3)
I64 g_k1 = K;
#undef K
#define K (N * 10)

#define F 1.5
F64 g_f1 = F;
#undef F
#define F 2.5
F64 g_f2 = F;

I64 InFunc()
{
  I64 x = 0;
#define M 100
  x += M;
#undef M
#define M 1
  x += M;
#undef M
  return x;
}

I32 Main()
{
  I64 correct = 0, tests = 9;

  "Test - #undef and redefinitions apply in source order: ";

  if (g_a == 3) correct++;
  if (g_b == 4) correct++;
  if (!StrCmp(g_s1, "one")) correct++;
  if (!StrCmp(g_s2, "two")) correct++;
  if (g_c == 10) correct++;
  if (V == 7) correct++;
  if (g_k1 == 6 && K == 40) correct++;
  if (g_f1 == 1.5 && g_f2 == 2.5) correct++;
  if (InFunc() == 101) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
