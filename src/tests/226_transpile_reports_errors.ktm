#include "testhelper.ktm"

/* -transpile reported no errors: a bad statement was dropped from the C
 * without a word, and `I64 v = ;` segfaulted (so did -jit). The program
 * is now checked by the compiler's front end first; its diagnostics are
 * printed as for -jit and an error stops the transpile. */

#define TD_FILE "__226_transpile.ktm"
#define TD_C    "__226_transpile.c"
#define TD_ERR  "__226_transpile.err"
#define TD_JIT  "__226_transpile.jit"
#define TD_EXE  "__226_transpile.exe"
#define TD_OUT  "__226_transpile.out"
#define TD_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
#define TD_KTM TD_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U0 CleanupTranspileDiagTest(U0)
{
  System("rm -f " TD_FILE " " TD_C " " TD_ERR " " TD_JIT " " TD_EXE " " TD_OUT);
}

/* -transpile fails with status 1 (no crash), prints exactly what -jit
 * prints (which contains `want`) and writes no C */
Bool FailsLikeJit(U8 *source, U8 *want)
{
  I64 res, jit_res;
  U8 *err, *jit, *code;
  Bool ok;
  CleanupTranspileDiagTest;
  FileWrite(TD_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  res = System(TD_KTM "-transpile " TD_FILE " > " TD_C " 2> " TD_ERR);
  jit_res = System(TD_KTM "-jit " TD_FILE " > /dev/null 2> " TD_JIT);
  err = FileRead(TD_ERR);
  jit = FileRead(TD_JIT);
  code = FileRead(TD_C);
  ok = res == 1 && jit_res == 1 && err && jit && !StrCmp(err, jit) &&
       strstr(err, want) && (!code || StrLen(code) == 0);
  if (!ok) "transpile %d jit %d:\n%s\n--- jit:\n%s\n", res, jit_res, err, jit;
  Free(err);
  Free(jit);
  Free(code);
  return ok;
}

U8 *td_good =
"#define GREETING \"hi\"
class Pt { I64 x; I64 y; };
I64 Sum(Pt *p) { return p->x + p->y; }
U0 Main()
{
  Pt pt;
  pt.x = 3;
  pt.y = 4;
  I64 z = 1;
  F64 f = z;
  \"%s %d %.1f\\n\", GREETING, Sum(&pt), f;
}
";

/* Valid, with macros pasted into strings, which the transpile parse
 * leaves unexpanded */
U8 *td_paste =
"#define M \"mid\"
#define N 3
U0 Main()
{
  I64 x = N;
  \"a\" M \"b %d\\n\", x;
}
";

U0 Main()
{
  "Test - transpile reports errors: ";
  I64 correct = 0, tests = 0, res;
  U8 *out, *err;

  tests++;
  if (FailsLikeJit("U0 Main() { I64 v = ; }\n",
                   "__226_transpile.ktm:1:19\n")) correct++;
  tests++;
  if (FailsLikeJit("U0 Main()\n{\n  I64 a = 1;\n  b = 2;\n}\n",
                   "Variable or function `b` has not been defined")) correct++;
  tests++;
  if (FailsLikeJit("class P { I64 x, y; };\nU0 Main()\n{\n  P p;\n  I64 z = p;\n}\n",
                   "`I64 = P` is invalid")) correct++;
  tests++;
  if (FailsLikeJit("class Pt { I64 x; };\nU0 Main()\n{\n  Pt pt;\n  Pt *q = &pt;\n"
                   "  Pt **pp = &q;\n  I64 v = **pp(I64 *);\n}\n",
                   "`*` on `I64` is invalid")) correct++;
  tests++;
  if (FailsLikeJit("U0 Main()\n{\n  I64 x = (1 + 2;\n}\n", "error:")) correct++;

  /* A valid program still transpiles, with no diagnostics, and its C
   * prints what the JIT does (only with a C compiler on the PATH) */
  CleanupTranspileDiagTest;
  FileWrite(TD_FILE, td_good, StrLen(td_good), O_CREAT | O_RDWR | O_TRUNC);
  tests++;
  res = System(TD_KTM "-transpile " TD_FILE " > " TD_C " 2> " TD_ERR);
  err = FileRead(TD_ERR);
  if (res == 0 && err && StrLen(err) == 0) correct++;
  else "good: %d %s\n", res, err;
  Free(err);
  tests++;
  if (System("command -v cc >/dev/null 2>&1") != 0) {
    correct++;
  } else {
    res = System("cc -w -o " TD_EXE " " TD_C " -lm > " TD_OUT " 2>&1 && " TD_TIMEOUT "./" TD_EXE " > " TD_OUT " 2>&1");
    out = FileRead(TD_OUT);
    if (res == 0 && out && !StrCmp(out, "hi 7 1.0\n")) correct++;
    else "cc: %s\n", out;
    Free(out);
  }

  /* Macros pasted into strings are no error */
  CleanupTranspileDiagTest;
  FileWrite(TD_FILE, td_paste, StrLen(td_paste), O_CREAT | O_RDWR | O_TRUNC);
  tests++;
  res = System(TD_KTM "-transpile " TD_FILE " > " TD_C " 2> " TD_ERR);
  err = FileRead(TD_ERR);
  if (res == 0 && err && !strstr(err, "error")) correct++;
  else "paste: %d %s\n", res, err;
  Free(err);

  CleanupTranspileDiagTest;
  PrintResult(correct, tests);
  "====\n";
}
