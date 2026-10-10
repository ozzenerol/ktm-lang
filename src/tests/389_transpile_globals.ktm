#include "testhelper.ktm"

/* `-transpile` dropped every file-scope global, so the C used undeclared
 * variables, and it dropped code at file scope (non-constant initialisers,
 * statements). Globals are now written with their type, linkage and
 * constant initialiser; file-scope code goes into __ktm_init(), which
 * main() runs first (a main() is generated when there is no Main, and the
 * user's Main becomes __ktm_Main when file-scope code calls it). Also
 * `&Fn` was written as `&&fn`. Each program here is transpiled, built
 * with cc and run. */

#define TG_FILE "__389_tg.ktm"
#define TG_C    "__389_tg.c"
#define TG_EXE  "__389_tg.exe"
#define TG_OUT  "__389_tg.out"
#define TG_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "

I64 g_correct = 0, g_tests = 0;

U0 CleanupTg()
{
  System("rm -f " TG_FILE " " TG_C " " TG_EXE " " TG_OUT);
}

/* Transpile `src`, build the C and run it with "one": its output must be
 * `want` */
U0 TranspilesTo(U8 *src, U8 *want, U8 *what)
{
  U8 *out = NULL;
  g_tests++;
  CleanupTg;
  FileWrite(TG_FILE, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC);
  if (System(TG_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -transpile " TG_FILE " > " TG_C " 2>/dev/null") == 0 &&
      System("cc -w -o " TG_EXE " " TG_C " -lm > " TG_OUT " 2>&1 && " TG_TIMEOUT "./" TG_EXE " one > " TG_OUT " 2>&1") == 0) {
    out = FileRead(TG_OUT);
  }
  if (out && !StrCmp(out, want)) {
    g_correct++;
  } else {
    "\n  failed: %s\n", what;
    if (out) "  got: %s", out;
    System("cat " TG_OUT " 2>/dev/null | head -5");
  }
  Free(out);
  CleanupTg;
}

I32 Main()
{
  "Test - transpile globals: ";
  if (System("command -v cc >/dev/null 2>&1") != 0) {
    /* no C compiler here: nothing to check */
    PrintResult(1, 1);
    "====\n";
    return 0;
  }

  TranspilesTo(
"class Pt { I64 x, y; };
I64 a;
I64 b = 5;
static I32 c = -1;
public F64 d = 1.5;
U8 *s = \"hi\";
U8 buf[8] = \"abc\";
I64 arr[3] = {1, 2, 3};
Pt origin = {4, 5};
Pt *pp;
I64 e = StrLen(\"abcd\");
\"top %d\\n\", e;
I32 Main(I32 argc, U8 **argv)
{
  a = 7;
  \"%d %d %d %.1f %s %s %d %d %d %d %d %s\\n\", a, b, c, d, s, buf, arr[2],
    origin.y, pp == NULL, e, argc, argv[1];
  return 0;
}
",
"top 4\n7 5 -1 1.5 hi abc 3 5 1 4 2 one\n",
    "every kind of global, file-scope code before Main");

  TranspilesTo(
"I64 Twice(I64 v) { return v * 2; }
I64 x = Twice(4);
\"script %d\\n\", x;
",
"script 8\n", "no Main: a main() runs the file-scope code");

  TranspilesTo(
"I64 n = 3;
U0 Main() { \"main %d\\n\", n; }
\"before\\n\";
Main;
\"after\\n\";
",
"before\nmain 3\nafter\n", "file-scope code that calls Main runs it once");

  TranspilesTo(
"I64 Twice(I64 v) { return v * 2; }
I64 (*fp)(I64) = &Twice;
auto z = 6;
Bool flag = 5;
I64 m = 1, k = Twice(m + 1);
class Pt { I64 x, y; };
Pt q = {m, 2};
U8 *np = NULL;
U0 Main() { \"%d %d %d %d %d %d %d\\n\", fp(5), z, flag, m, k, q.x + q.y, np == NULL; }
",
"10 6 1 1 4 3 1\n",
    "function pointer, auto, Bool, a list, a {...} with a variable item");

  TranspilesTo(
"I64 Twice(I64 v) { return v * 2; }
U0 Main() { I64 (*f)(I64) = &Twice; \"%d\\n\", f(21); }
",
"42\n", "&Fn in a function");

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
