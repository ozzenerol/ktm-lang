#include "testhelper.ktm"

/* A character constant in an error message was printed from its
 * decoded length: `'\n'` (one character) showed as `\`, `'\x41'` as
 * `\`. Messages now show a character constant or a string as written,
 * with its quotes, and the underline covers it. */

#define CCM_SRC "./ktm-224-ccm.ktm"
#define CCM_OUT "./ktm-224-ccm.out"

/* Compile `source` and return what ktm printed */
U8 *Diagnose(U8 *source)
{
  System("rm -f " CCM_SRC " " CCM_OUT);
  FileWrite(CCM_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " CCM_SRC " -o /dev/null > " CCM_OUT " 2>&1");
  U8 *out = FileRead(CCM_OUT);
  System("rm -f " CCM_SRC " " CCM_OUT);
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

#define UNX " perhaps you meant ';' or ','?"

I32 Main()
{
  "Test - character constant in error messages: ";
  I64 tests = 0;
  I64 correct = 0;

  tests++;
  if (Has("I64 s = 2 '\\n' 3;\n",
          "Unexpected character constant `'\\n'`" UNX,
          "224-ccm.ktm:1:11\n", "|              ^^^^\n"))
    correct++;
  else "Failed - '\\n'\n";

  tests++;
  if (Has("I64 s = 2 'ab' 3;\n", "Unexpected character constant `'ab'`" UNX,
          "|              ^^^^\n"))
    correct++;
  else "Failed - 'ab'\n";

  tests++;
  if (Has("I64 s = 2 '\\x41' 3;\n",
          "Unexpected character constant `'\\x41'`" UNX,
          "|              ^^^^^^\n"))
    correct++;
  else "Failed - '\\x41'\n";

  tests++;
  if (Has("I64 s = 2 '\\'' 3;\n", "Unexpected character constant `'\\''`" UNX,
          "|              ^^^^\n"))
    correct++;
  else "Failed - '\\''\n";

  tests++;
  if (Has("I64 s = 2 \"a\\tb\" 3;\n", "Unexpected string `\"a\\tb\"`" UNX,
          "|              ^^^^^^\n"))
    correct++;
  else "Failed - string with an escape\n";

  tests++;
  if (Has("U0 F() { if (1) '\\n' }\n",
          "Unexpected character constant `'\\n'` while parsing if body",
          "224-ccm.ktm:1:17\n"))
    correct++;
  else "Failed - if body\n";

  /* The value is unchanged */
  tests++;
  if ('\n' == 10 && '\x41' == 'A' && '\'' == 39 && 'ab' == 0x6261)
    correct++;
  else "Failed - values\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
