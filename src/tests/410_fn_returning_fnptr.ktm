#include "testhelper.ktm"

/* A function returning a function pointer, declared C's way,
 * `I64 (*GetH())(I64) { ... }`, was a syntax error. Now: definitions and
 * prototypes, with parameters, old and new type names, void and pointer
 * returns of the pointed-to function, and `(**Name(...))(...)` returning
 * a pointer to a function pointer. */

I64 Twice(I64 x) { return x * 2; }
I64 Thrice(I64 x) { return x * 3; }
U8 g_last[16];
void Hello(u8 *who) { StrCpy(g_last, who); }
u8 *Echo(u8 *s) { return s; }

I64 (*GetH())(I64) { return &Twice; }

I64 (*Pick(I64 which))(I64);          /* prototype first */
I64 (*Pick(I64 which))(I64)
{
  if (which) return &Thrice;
  return &Twice;
}

i64 (*PickNew(bool b, i64 unused))(i64)
{
  if (b) return &Thrice;
  return &Twice;
}

void (*Greeter())(u8 *who) { return &Hello; }
u8 *(*Echoer())(u8 *) { return &Echo; }

I64 (*g_fp)(I64) = NULL;
I64 (**AddrOfG())(I64) { return &g_fp; }

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - functions returning function pointers: ";

  tests++;
  I64 (*h)(I64) = GetH();
  I64 (*p1)(I64) = Pick(1);
  I64 (*p0)(I64) = Pick(0);
  i64 (*p2)(i64) = PickNew(false, 9);
  if (h(7) == 14 && p1(5) == 15 && p0(5) == 10 && p2(4) == 8) correct++;
  else "\n  returned pointers\n";

  tests++;
  void (*gr)(u8 *) = Greeter();
  gr("ktm");
  u8 *(*ec)(u8 *) = Echoer();
  if (!StrCmp(g_last, "ktm") && !StrCmp(ec("x"), "x")) correct++;
  else "\n  void and pointer returns\n";

  tests++;   /* a pointer to a function pointer, written through */
  I64 (**pg)(I64) = AddrOfG();
  *pg = &Thrice;
  if (g_fp(10) == 30 && pg == &g_fp) correct++;
  else "\n  pointer to function pointer return\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
