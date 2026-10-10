#include "testhelper.ktm"

/* A stray `;` at the top level, `I64 x = 1;;`, was taken as the type of
 * the next declaration, which then failed with "Identifier expected:
 * got TK_KEYWORD I64". It is now an empty declaration and skipped,
 * after a variable, a function body, a class, alone, and in the REPL.
 * This file has some itself; the rest compile in a separate ktm. */

;
I64 g_semi_x = 1;;
I64 g_semi_y = 2;
U0 SemiF() { g_semi_x += 10; };
class SemiC { I64 a; };;
;;
I64 SemiG();;
I64 SemiG() { return 5; };

#define SEMI_TEST_SRC "./ktm-194-semi.ktm"
#define SEMI_TEST_OUT "./ktm-194-semi.out"
#define SEMI_TEST_BIN "./ktm-194-semi.bin"
/* A single literal: adjacent strings don't concatenate inside #define */
#define SEMI_KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U0 CleanupSemiTest(U0)
{
  System("rm -f " SEMI_TEST_SRC " " SEMI_TEST_OUT " " SEMI_TEST_BIN);
}

/* Runs `cmd` on `source` (written to SEMI_TEST_SRC), TRUE if it exits
 * with `status` and its output is exactly `expected` (NULL to skip) or,
 * with `contains`, has it. */
Bool RunSemi(U8 *source, U8 *cmd, I64 status, U8 *expected,
             Bool contains = FALSE)
{
  U8 *out, buf[1024];
  I64 res;
  Bool ok;

  CleanupSemiTest;
  FileWrite(SEMI_TEST_SRC, source, StrLen(source),
            O_CREAT | O_RDWR | O_TRUNC);
  snprintf(buf, sizeof(buf), "(%s) > " SEMI_TEST_OUT " 2>&1; test $? -eq %d",
           cmd, status);
  res = System(buf);
  out = FileRead(SEMI_TEST_OUT);
  ok = res == 0 && out &&
       (!expected || (contains && strstr(out, expected)) ||
        (!contains && !StrCmp(out, expected)));
  if (!ok) "got (%d): '%s'\n", res, out;
  Free(out);
  CleanupSemiTest;
  return ok;
}

#define SEMI_AOT SEMI_KTM SEMI_TEST_SRC " -o " SEMI_TEST_BIN " && " SEMI_TEST_BIN
#define SEMI_JIT SEMI_KTM "-jit " SEMI_TEST_SRC
#define SEMI_REPL SEMI_KTM "-repl < " SEMI_TEST_SRC

I32 Main()
{
  "Test - top level stray semicolon: ";
  I64 tests = 0;
  I64 correct = 0;
  SemiC c;

  /* The declarations of this file around its own stray `;`s */
  tests++;
  c.a = 7;
  SemiF;
  if (g_semi_x == 11 && g_semi_y == 2 && c.a == 7 && SemiG == 5) correct++;
  else "Failed - this file\n";

  /* The repro, a file starting with `;` and a main after `};` */
  U8 *repro = "I64 x = 1;;\nI64 y = 2;\nU0 Main()\n{\n"
              "  \"%d %d\\n\", x, y;\n};\n";
  tests++;
  if (RunSemi(repro, SEMI_AOT, 0, "1 2\n")) correct++;
  else "Failed - the repro (AOT)\n";
  tests++;
  if (RunSemi(repro, SEMI_JIT, 0, "1 2\n")) correct++;
  else "Failed - the repro (JIT)\n";
  tests++;
  if (RunSemi(";\nclass P { I64 v; };;\nU0 Main()\n{\n  P p;\n"
              "  p.v = 4;\n  \"%d\\n\", p.v;\n}\n;;\n", SEMI_AOT, 0, "4\n"))
    correct++;
  else "Failed - leading ; and };;\n";

  /* Top level statements (scripting) after stray `;`s */
  tests++;
  if (RunSemi("I64 x = 3;;\n;\n\"%d\\n\", x;;\n", SEMI_JIT, 0, "3\n"))
    correct++;
  else "Failed - top level statements\n";

  /* The REPL */
  tests++;
  if (RunSemi("I64 x = 1;;\nI64 y = 2;\nx + y;\n;\nU0 F() { \"f\\n\"; };\nF;\n",
              SEMI_REPL, 0, "3\nf\n")) correct++;
  else "Failed - repl\n";

  /* A missing declarator after `,` is still an error */
  tests++;
  if (RunSemi("I64 a, ;\nI64 b;\n", SEMI_AOT, 1, "Identifier expected", TRUE))
    correct++;
  else "Failed - I64 a, ;\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
