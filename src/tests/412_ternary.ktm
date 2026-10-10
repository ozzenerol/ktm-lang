#include "testhelper.ktm"

/* The conditional operator `cond ? a : b`: right-associative, lower
 * precedence than ||, only the chosen branch evaluated, usual arithmetic
 * conversions between the branches, pointer and null branches, nested,
 * and usable in constant expressions (array sizes, global initialisers,
 * #if). Not an lvalue (that is a compile error, checked separately). */

I64 g_arr[1 > 0 ? 4 : 9];      /* size folds to 4 */
I64 g_init = 3 > 2 ? 42 : 99;  /* global initialiser folds to 42 */
#if 1 ? 2 : 0
#define PP_TAKEN 1
#else
#define PP_TAKEN 0
#endif

I64 g_calls = 0;
I64 Bump(I64 x) { g_calls++; return x; }
I64 Dbl(I64 x) { return x * 2; }
I64 Trp(I64 x) { return x * 3; }

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

U0 Basics()
{
  I64 a = 5, b = 10;
  Check((a > b ? a : b) == 10, "max");
  Check((a < b ? a : b) == 5, "min");
  Check((a > b ? 1 : 0) == 0, "false branch");
  Check((TRUE ? 7 : 8) == 7, "constant condition true");
  /* Lower precedence than ||: `a || b ? c : d` is `(a||b) ? c : d`. */
  Check((0 || 1 ? 11 : 22) == 11, "binds looser than ||");
  Check((1 && 0 ? 11 : 22) == 22, "binds looser than &&");
}

U0 Nested()
{
  I64 x = 2;
  /* Right-associative: a ? b : c ? d : e is a ? b : (c ? d : e). */
  Check((x == 1 ? 10 : x == 2 ? 20 : 30) == 20, "right-assoc else chain");
  Check((0 ? 1 : 0 ? 2 : 3) == 3, "all-false chain");
  /* Parenthesised middle. */
  Check((x == 2 ? x == 2 ? 100 : 101 : 102) == 100, "nested in the middle");
}

U0 OnlyOneBranch()
{
  I64 a = 1;
  g_calls = 0;
  I64 r = a ? Bump(7) : Bump(9);
  Check(r == 7 && g_calls == 1, "only the true branch ran");
  g_calls = 0;
  r = 0 ? Bump(7) : Bump(9);
  Check(r == 9 && g_calls == 1, "only the false branch ran");
  /* A branch that would fault must not run when not chosen. */
  I64 *p = NULL;
  Check((a ? 55 : *p) == 55, "unchosen branch (deref NULL) is skipped");
}

U0 Conversions()
{
  I64 i = 3;
  F64 f = 1.5;
  U8 c = 65;
  U64 big = 0xFFFFFFFFFFFFFFFF;
  /* int and float branches: result is float. */
  Check((1 ? f : i) == 1.5, "int/float branch is float");
  Check((0 ? f : i) == 3.0, "float result from the int branch");
  /* char stays its value. */
  Check((1 ? c : 0) == 65, "char branch");
  /* unsigned 64-bit kept. */
  Check((1 ? big : 0) == 0xFFFFFFFFFFFFFFFF, "u64 branch");
  /* both negative ints. */
  Check((0 ? 1 : -7) == -7, "negative int branch");
}

U0 Pointers()
{
  I64 a = 1;
  U8 *s = a ? "yes" : "no";
  Check(!StrCmp(s, "yes"), "string branches");
  Check(!StrCmp(a > 1 ? "yes" : "no", "no"), "string false branch");
  /* pointer and null. */
  U8 *p = a ? s : NULL;
  Check(p != NULL, "pointer / null, pointer chosen");
  U8 *q = a > 1 ? s : NULL;
  Check(q == NULL, "pointer / null, null chosen");
}

U0 ConstExpr()
{
  Check(sizeof(g_arr) / sizeof(I64) == 4, "array size from a ternary");
  Check(g_init == 42, "global initialiser from a ternary");
  Check(PP_TAKEN == 1, "#if with a ternary");
}

U0 FnPtrCallThrough()
{
  I64 a = 1;
  /* Calls the chosen function. Needs the call-on-parenthesised-expr
   * feature (feat/call-paren-expr) in the rc. */
  Check((a ? &Dbl : &Trp)(10) == 20, "call through (cond ? f : g)(x), true");
  Check((a > 1 ? &Dbl : &Trp)(10) == 30, "call through, false");
}

I32 Main()
{
  "Test - conditional operator: ";
  Basics();
  Nested();
  OnlyOneBranch();
  Conversions();
  Pointers();
  ConstExpr();
  FnPtrCallThrough();
  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
