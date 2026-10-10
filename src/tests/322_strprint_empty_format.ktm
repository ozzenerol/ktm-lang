#include "testhelper.ktm"

/* StrPrintJoin wrote the NUL only after a character, so an empty result
 * (StrPrint(NULL, ""), or "%s" of "") was an unterminated MAlloc block:
 * whatever was in it came out (garbage on arm64 Linux, where Shlurp("")
 * ran it as a command; test 316 covers Shlurp). */

I32 Main()
{
  I64 correct = 0, tests = 0, i;
  U8 *s, *junk, buf[16];
  "Test - StrPrint with an empty result: ";

  /* Fill freed blocks with junk so a reused one isn't zero by chance */
  for (i = 0; i < 64; i++) {
    junk = MAlloc(256);
    MemSet(junk, 'x', 256);
    Free(junk);
  }

  tests++;
  s = StrPrint(NULL, "");
  if (s && StrLen(s) == 0) correct++;
  Free(s);

  tests++;
  s = StrPrint(NULL, "%s", "");
  if (s && StrLen(s) == 0) correct++;
  Free(s);

  tests++;
  s = MStrPrint("");
  if (s && StrLen(s) == 0) correct++;
  Free(s);

  tests++;
  MemSet(buf, 'y', 16);
  StrPrint(buf, "");
  if (buf[0] == '\0') correct++;

  tests++;
  s = StrPrint(NULL, "%s%s", "", "");
  if (s && StrLen(s) == 0) correct++;
  Free(s);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
