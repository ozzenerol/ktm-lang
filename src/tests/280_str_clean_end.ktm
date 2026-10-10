#include "testhelper.ktm"

/* StrCleanEnd(str, chars) removes every character in `chars` from the
 * end of str, in place, stops at the first one that isn't and returns the
 * new length. */

Bool Clean(U8 *in, U8 *chars, U8 *want)
{
  U8 buf[64];
  buf[0] = '#';                 /* guard byte before the string */
  StrCpy(buf + 1, in);
  I64 n = StrCleanEnd(buf + 1, chars);
  Bool ok = n == StrLen(want) && !StrCmp(buf + 1, want) && buf[0] == '#';
  if (!ok) "Clean(\"%s\", \"%s\") -> \"%s\" %d\n", in, chars, buf + 1, n;
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - StrCleanEnd: ";

  tests++;
  if (Clean("exit\r\n", "\r\n", "exit") && Clean("exit\n", "\r\n", "exit") &&
      Clean("exit\n\r\n\n", "\r\n", "exit") && Clean("exit", "\r\n", "exit"))
    correct++;

  tests++;   /* only the end, and only the given characters */
  if (Clean("\na\nb  \n", "\n", "\na\nb  ") && Clean("x..!!", "!.", "x") &&
      Clean("a b ", "\n", "a b "))
    correct++;

  tests++;   /* everything removed, empty input */
  if (Clean("\r\n\r\n", "\r\n", "") && Clean("", "\r\n", ""))
    correct++;

  tests++;   /* nothing to remove with no characters */
  if (Clean("abc\n", "", "abc\n") && Clean("abc\n", NULL, "abc\n"))
    correct++;

  tests++;
  if (StrCleanEnd(NULL, "\n") == 0 && StrCleanEnd(NULL, NULL) == 0)
    correct++;

  tests++;   /* a read() buffer: NUL it, then drop the line ending */
  U8 buffer[16];
  MemCpy(buffer, "test\r\n", 6);
  buffer[6] = '\0';
  I64 len = StrCleanEnd(buffer, "\r\n");
  if (len == 4 && !StrCmp(buffer, "test")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
