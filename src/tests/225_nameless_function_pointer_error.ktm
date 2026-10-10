#include "testhelper.ktm"

/* A function pointer declared without a name, `I64 (*)(I64 x);`, was
 * reported ("Expected function pointer name") and then, after the
 * recovery restarted inside its parameter list, again as "Cannot handle
 * ')'". Now it is reported once, at the token in place of the name, as a
 * global, a local, a parameter and a class field, and the code after it
 * is still checked. */

#define FPN_SRC "./ktm-225-fpn.ktm"
#define FPN_OUT "./ktm-225-fpn.out"

/* Compile `source` and return what ktm printed */
U8 *Diagnose(U8 *source)
{
  System("rm -f " FPN_SRC " " FPN_OUT);
  FileWrite(FPN_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " FPN_SRC " -o /dev/null > " FPN_OUT " 2>&1");
  U8 *out = FileRead(FPN_OUT);
  System("rm -f " FPN_SRC " " FPN_OUT);
  return out;
}

I64 Count(U8 *s, U8 *what)
{
  I64 n = 0;
  while (s && (s = strstr(s, what))) {
    n++;
    s++;
  }
  return n;
}

/* ktm prints `errors` errors, `warnings` warnings, and `want` */
Bool Prints(U8 *source, I64 errors, I64 warnings, U8 *want)
{
  U8 *out = Diagnose(source);
  Bool ok = out && Count(out, "error:") == errors &&
            Count(out, "warning:") == warnings && strstr(out, want);
  if (!ok) "got: '%s'\n", out;
  Free(out);
  return ok;
}

#define MSG "Expected function pointer name got: TK_PUNCT      )"

I32 Main()
{
  "Test - function pointer without a name: ";
  I64 tests = 0;
  I64 correct = 0;

  tests++;
  if (Prints("I64 (*)(I64 x);\nI64 b = 1;\n", 1, 0, MSG "\n --> ./ktm-225-fpn.ktm:1:7\n"))
    correct++;
  else "Failed - global\n";

  tests++;
  if (Prints("I64 (*)(I64 x) = NULL;\n", 1, 0, "225-fpn.ktm:1:7\n"))
    correct++;
  else "Failed - global with an initialiser\n";

  tests++;
  if (Prints("I64 (*5)(I64 x);\n", 1, 0,
             "Expected function pointer name got: TK_I64        5\n"
             " --> ./ktm-225-fpn.ktm:1:7\n"))
    correct++;
  else "Failed - a number for the name\n";

  tests++;
  if (Prints("U0 F() {\n  I64 (*)(I64 x);\n  I64 y = 2;\n}\n", 1, 0,
             MSG "\n --> ./ktm-225-fpn.ktm:2:9\n"))
    correct++;
  else "Failed - local\n";

  tests++;
  if (Prints("U0 F(I64 (*)(I64 x), I64 (*)(), I64 y) { y = 1; }\n", 2, 0,
             "225-fpn.ktm:1:28\n"))
    correct++;
  else "Failed - parameters\n";

  tests++;
  if (Prints("class C {\n  I64 (*)(I64 x);\n  I64 y;\n};\nC c;\n", 1, 0,
             MSG "\n --> ./ktm-225-fpn.ktm:2:9\n"))
    correct++;
  else "Failed - class field\n";

  /* Errors after it are still found */
  tests++;
  if (Prints("I64 (*)(I64 x);\nI64 (*)(I64 x);\nI64 b = 1 2;\n", 3, 0,
             "225-fpn.ktm:3:11\n"))
    correct++;
  else "Failed - errors after it\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
