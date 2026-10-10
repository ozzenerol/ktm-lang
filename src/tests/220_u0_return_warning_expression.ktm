#include "testhelper.ktm"

/* The warning for a value returned from a function that can't return it
 * printed the expression rebuilt from the AST: `return g++;` as `++g`,
 * `return Twice(Twice(1));` as `Twice(Twice(1););`, and could point
 * inside the expression. It now prints the expression as written and
 * points at the `return`, underlining it and the expression. */

#define URE_SRC "./ktm-220-ure.ktm"
#define URE_OUT "./ktm-220-ure.out"

/* Compile `source` and return what ktm printed */
U8 *Diagnose(U8 *source)
{
  System("rm -f " URE_SRC " " URE_OUT);
  FileWrite(URE_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " URE_SRC " -o /dev/null > " URE_OUT " 2>&1");
  U8 *out = FileRead(URE_OUT);
  System("rm -f " URE_SRC " " URE_OUT);
  return out;
}

/* ktm's output has every one of `want` */
Bool Has(U8 *source, U8 *want1, U8 *want2, U8 *want3 = NULL)
{
  U8 *out = Diagnose(source);
  Bool ok = out && strstr(out, want1) && strstr(out, want2) &&
            (!want3 || strstr(out, want3));
  if (!ok) "got: '%s'\n", out;
  Free(out);
  return ok;
}

#define PRE "I64 g = 0;\nI64 Twice(I64 x) { return x * 2; }\n"

I32 Main()
{
  "Test - U0 return warning expression: ";
  I64 tests = 0;
  I64 correct = 0;

  tests++;
  if (Has(PRE "U0 A() { return g++; }\n",
          "unexpected return value 'g++' of type 'i64' expected 'void'",
          "220-ure.ktm:3:10\n", "|             ^^^^^^^^^^\n"))
    correct++;
  else "Failed - g++\n";

  tests++;
  if (Has(PRE "U0 B() { return Twice(Twice(1)); }\n",
          "unexpected return value 'Twice(Twice(1))' of type",
          "220-ure.ktm:3:10\n"))
    correct++;
  else "Failed - nested call\n";

  tests++;
  if (Has(PRE "U0 C() { return  \"a;b\" ; }\n",
          "unexpected return value '\"a;b\"' of type", "220-ure.ktm:3:10\n"))
    correct++;
  else "Failed - string with a ;\n";

  tests++;
  if (Has(PRE "I64 *E() { F64 f; return f; }\n",
          "unexpected return value 'f' of type 'f64' expected 'i64 *'",
          "220-ure.ktm:3:19\n"))
    correct++;
  else "Failed - pointer return\n";

  /* Over two lines: printed from the AST, at the `return` */
  tests++;
  if (Has(PRE "U0 D() { return g +\n  1; }\n",
          "unexpected return value 'g + 1' of type", "220-ure.ktm:3:10\n"))
    correct++;
  else "Failed - two lines\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
