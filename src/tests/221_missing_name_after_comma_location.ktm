#include "testhelper.ktm"

/* A global declaration missing a name after a comma, `I64 a, ;`, was
 * reported at the token after the bad one: the start of the next line,
 * or nowhere at the end of the file. It is now reported at the bad
 * token, and the declarations after it are still checked. */

#define CNM_SRC "./ktm-221-cnm.ktm"
#define CNM_OUT "./ktm-221-cnm.out"

/* Compile `source` and return what ktm printed */
U8 *Diagnose(U8 *source)
{
  System("rm -f " CNM_SRC " " CNM_OUT);
  FileWrite(CNM_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " CNM_SRC " -o /dev/null > " CNM_OUT " 2>&1");
  U8 *out = FileRead(CNM_OUT);
  System("rm -f " CNM_SRC " " CNM_OUT);
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

I32 Main()
{
  "Test - missing name after a comma: ";
  I64 tests = 0;
  I64 correct = 0;

  tests++;
  if (Has("I64 a, ;\nI64 b;\n", "Identifier expected", "221-cnm.ktm:1:8\n",
          "|           ^\n"))
    correct++;
  else "Failed - `;` after the comma\n";

  tests++;
  if (Has("I64 a, ;", "Identifier expected", "221-cnm.ktm:1:8\n"))
    correct++;
  else "Failed - at the end of the file\n";

  tests++;
  if (Has("I64 a, 5;\n", "Identifier expected", "221-cnm.ktm:1:8\n"))
    correct++;
  else "Failed - a number after the comma\n";

  tests++;
  if (Has("I64 a,\n;\nI64 c;\n", "Identifier expected", "221-cnm.ktm:2:1\n"))
    correct++;
  else "Failed - `;` on the next line\n";

  /* The next declaration is still checked, at its own token */
  tests++;
  if (Has("I64 a, ;\nI64 b, 7;\n", "221-cnm.ktm:1:8\n", "221-cnm.ktm:2:8\n"))
    correct++;
  else "Failed - two errors\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
