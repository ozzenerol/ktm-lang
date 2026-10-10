#include "testhelper.ktm"

/* A call argument of the wrong type was reported at the call's `)` (or
 * the token after it) instead of at the argument. It is now reported at
 * the argument, underlining all of it. */

#define ARGLOC_SRC "./ktm-216-argloc.ktm"
#define ARGLOC_OUT "./ktm-216-argloc.out"

/* Compile `source` and return what ktm printed */
U8 *Diagnose(U8 *source)
{
  System("rm -f " ARGLOC_SRC " " ARGLOC_OUT);
  FileWrite(ARGLOC_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " ARGLOC_SRC " -o /dev/null > " ARGLOC_OUT " 2>&1");
  U8 *out = FileRead(ARGLOC_OUT);
  System("rm -f " ARGLOC_SRC " " ARGLOC_OUT);
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

#define CLS "class P { I64 x, y; };\nclass S { U0 (*f)(P *p); };\n" \
            "U0 Take(P *p) { }\nU0 TakeV(P p) { }\nU0 Two(I64 a, P *p) { }\n"

I32 Main()
{
  "Test - argument type warning location: ";
  I64 tests = 0;
  I64 correct = 0;

  tests++;
  if (Has(CLS "U0 Main() {\n  I64 y = 1;\n  Take(y);\n}\n",
          "expected 'P *' got 'I64' in function 'Take'",
          "216-argloc.ktm:8:8\n", "|           ^\n"))
    correct++;
  else "Failed - pointer parameter\n";

  tests++;
  if (Has(CLS "U0 Main() {\n  I64 y = 1;\n  TakeV(y);\n}\n",
          "expected 'P' got 'I64' in function 'TakeV'",
          "216-argloc.ktm:8:9\n"))
    correct++;
  else "Failed - class parameter\n";

  tests++;
  if (Has(CLS "U0 Main() {\n  I64 y = 1;\n  Two(1, y);\n}\n",
          "in function 'Two'", "216-argloc.ktm:8:10\n"))
    correct++;
  else "Failed - second argument\n";

  tests++;
  if (Has(CLS "U0 Main() {\n  I64 y = 1;\n  Take(y + 1);\n}\n",
          "216-argloc.ktm:8:8\n", "|           ^^^^^\n"))
    correct++;
  else "Failed - expression argument\n";

  tests++;
  if (Has(CLS "U0 Main() {\n  I64 y = 1;\n  S s;\n  s.f(y);\n}\n",
          "in function 'f'", "216-argloc.ktm:9:7\n"))
    correct++;
  else "Failed - function pointer field\n";

  tests++;
  if (Has(CLS "U0 Main() {\n  I64 y = 1;\n  Two(1,\n      y);\n}\n",
          "in function 'Two'", "216-argloc.ktm:9:7\n"))
    correct++;
  else "Failed - argument on the next line\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
