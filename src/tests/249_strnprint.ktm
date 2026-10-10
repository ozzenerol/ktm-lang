#include "testhelper.ktm"

/* StrNPrint: StrPrint into a buffer of a known size, with C's snprintf
 * rules - at most size-1 characters, always a '\0' within size, nothing
 * written for size <= 0, longer output cut short, returns dst. */

U8 *Show(U8 *buf, I64 n)
{ // the n bytes, '\0' as '0' and others as is
  U8 *s = MAlloc(n + 1);
  for (I64 i = 0; i < n; i++) {
    if (buf[i]) s[i] = buf[i];
    else        s[i] = '0';
  }
  s[n] = '\0';
  return s;
}

Bool BufIs(U8 *buf, I64 n, U8 *want)
{
  U8 *s = Show(buf, n);
  Bool ok = !StrCmp(s, want);
  if (!ok) "got [%s] want [%s]\n", s, want;
  Free(s);
  return ok;
}

I32 Main()
{
  "Test - StrNPrint: ";
  I64 correct = 0, tests = 0, i;
  U8 buf[16];

  /* Fits */
  tests++;
  MemSet(buf, '#', 16);
  if (StrNPrint(buf, 16, "%d-%s", 42, "ab") == buf &&
      BufIs(buf, 16, "42-ab0##########")) correct++;
  /* Cut short, '\0' at size-1, nothing after it */
  tests++;
  MemSet(buf, '#', 16);
  StrNPrint(buf, 6, "%s|%05d", "hello", 7);
  if (BufIs(buf, 16, "hello0##########")) correct++;
  /* Exactly fills */
  tests++;
  MemSet(buf, '#', 16);
  StrNPrint(buf, 4, "abc");
  if (BufIs(buf, 16, "abc0############")) correct++;
  /* size 1: only the '\0'; size 0 and negative: nothing */
  tests++;
  MemSet(buf, '#', 16);
  StrNPrint(buf, 1, "abc");
  StrNPrint(buf + 2, 0, "abc");
  StrNPrint(buf + 4, -5, "abc");
  if (BufIs(buf, 16, "0###############")) correct++;
  /* An empty format, and no format arguments */
  tests++;
  MemSet(buf, '#', 16);
  StrNPrint(buf, 16, "");
  if (BufIs(buf, 3, "0##")) correct++;
  /* Same text as StrPrint for any size */
  tests++;
  Bool ok = TRUE;
  U8 *full = StrPrint(NULL, "%x|%5.2f|%-4s|", 48879, 3.14159, "z");
  I64 flen = StrLen(full);
  for (i = 1; i <= 16; i++) {
    MemSet(buf, '#', 16);
    StrNPrint(buf, i, "%x|%5.2f|%-4s|", 48879, 3.14159, "z");
    I64 n = flen;
    if (n > i - 1) n = i - 1;
    if (StrNCmp(buf, full, n) || buf[n] != '\0') ok = FALSE;
    if (i < 16 && buf[i] != '#') ok = FALSE;
  }
  if (ok) correct++;
  Free(full);
  /* NULL dst is left alone */
  tests++;
  if (StrNPrint(NULL, 8, "x") == NULL) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
