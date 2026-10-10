#include "testhelper.ktm"

/* The underline under a string token in a diagnostic was the length of
 * its contents, starting at the opening quote, so it left out the end of
 * the string and the closing quote. It now covers the whole token, as
 * does the one under a character constant (whose escapes were counted
 * as one character). */

#define STRU_SRC "./ktm-219-stru.ktm"
#define STRU_OUT "./ktm-219-stru.out"

/* Compile `source` and return what ktm printed */
U8 *Diagnose(U8 *source)
{
  System("rm -f " STRU_SRC " " STRU_OUT);
  FileWrite(STRU_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " STRU_SRC " -o /dev/null > " STRU_OUT " 2>&1");
  U8 *out = FileRead(STRU_OUT);
  System("rm -f " STRU_SRC " " STRU_OUT);
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
  "Test - string token underline: ";
  I64 tests = 0;
  I64 correct = 0;

  tests++;
  if (Has("I64 s = 2 \"abcd\" 3;\n", "Unexpected string `\"abcd\"`",
          "219-stru.ktm:1:11\n", "|              ^^^^^^\n"))
    correct++;
  else "Failed - string\n";

  tests++;
  if (Has("I64 s = 2 \"a\\nb\" 3;\n", "Unexpected string",
          "|              ^^^^^^\n"))
    correct++;
  else "Failed - string with an escape\n";

  tests++;
  if (Has("I64 s = 2 \"\" 3;\n", "Unexpected string",
          "|              ^^\n"))
    correct++;
  else "Failed - empty string\n";

  tests++;
  if (Has("I64 s = 2 'ab' 3;\n", "Unexpected character constant",
          "|              ^^^^\n"))
    correct++;
  else "Failed - character constant\n";

  tests++;
  if (Has("I64 s = 2 '\\n' 3;\n", "Unexpected character constant",
          "|              ^^^^\n"))
    correct++;
  else "Failed - escaped character constant\n";

  tests++;
  if (Has("I64 s = 2 '\\'' 3;\n", "Unexpected character constant",
          "|              ^^^^\n"))
    correct++;
  else "Failed - escaped quote\n";

  /* Other tokens are unchanged */
  tests++;
  if (Has("I64 s = 2 abc 3;\n", "219-stru.ktm:1:11\n",
          "|              ^^^\n"))
    correct++;
  else "Failed - identifier\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
