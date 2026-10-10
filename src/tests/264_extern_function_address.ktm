#include "testhelper.ktm"

/* Taking the address of a function this object doesn't define - a C
 * library function declared `extern "c"`, or a libktm function only
 * prototyped in std.ktmh - was compiled to a PC-relative `leaq sym(%rip)`
 * (`adrp`/`add` on arm64). For a function in a shared library that can't
 * be linked into a position-independent executable: compiled, `&labs`
 * failed with "relocation R_X86_64_PC32 against symbol `labs' can not be
 * used when making a PIE object". The address now comes from the GOT. */

extern "c" I64 labs(I64 x);
extern "c" I32 strcmp(U8 *a, U8 *b);
extern "c" U64 strlen(U8 *s);

I64 (*g_init)(I64 x) = &labs;   /* in data: a plain relocation */
I64 (*g_run)(I64 x);

I64 Later(I64 x);                /* prototyped here, defined below */

I64 Apply(I64 (*f)(I64 x), I64 v) { return f(v); }

I32 Main()
{
  "Test - address of an extern function: ";
  I64 tests = 0;
  I64 correct = 0;

  I64 (*f)(I64 x) = &labs;
  g_run = &labs;
  tests++;
  if (f(-3) == 3 && g_run(-4) == 4 && Apply(&labs, -5) == 5 && g_init(-6) == 6)
    correct++;
  else "Failed - labs\n";

  tests++;
  if (f == g_run && f == &labs && g_init == f) correct++;
  else "Failed - the same address\n";

  I32 (*cmp)(U8 *a, U8 *b) = &strcmp;
  U64 (*len)(U8 *s) = &strlen;
  I32 r = cmp("a", "b");
  tests++;
  if (r < 0 && cmp("b", "b") == 0 && len("hello") == 5) correct++;
  else "Failed - strcmp/strlen\n";

  /* libktm functions, a ktm one and an asm-bound one */
  I64 (*hlen)(U8 *s) = &StrLen;
  U0 *(*alloc)(U64 size) = &MAlloc;
  U8 *p = alloc(16);
  tests++;
  if (hlen("four") == 4 && p != NULL) correct++;
  else "Failed - libktm\n";
  Free(p);

  /* Defined in this file after a prototype */
  tests++;
  if (Apply(&Later, 20) == 21) correct++;
  else "Failed - prototyped\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}

I64 Later(I64 x) { return x + 1; }
