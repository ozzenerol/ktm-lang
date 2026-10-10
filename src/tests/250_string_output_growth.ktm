#include "testhelper.ktm"

/* StrPrint's buffer grew to ten times its size plus the argument
 * whenever a %s didn't fit, and StrLen was a byte-at-a-time loop that
 * crashed on NULL. The buffer now doubles, and StrLen is libc's strlen,
 * with NULL giving 0. */

I32 Main()
{
  "Test - string output growth and StrLen: ";
  I64 correct = 0, tests = 0, i, j;

  /* NULL is 0 */
  tests++;
  if (StrLen(NULL) == 0 && StrLen("") == 0 && StrLen("abc") == 3) correct++;

  /* Every length, at every alignment */
  tests++;
  U8 *buf = MAlloc(300);
  Bool ok = TRUE;
  for (i = 0; i < 16; i++) {
    for (j = 0; j < 260; j++) {
      MemSet(buf + i, 'a', j);
      buf[i + j] = '\0';
      if (StrLen(buf + i) != j) ok = FALSE;
    }
  }
  if (ok) correct++;
  Free(buf);

  /* Long %s arguments: the result is at most about twice its length */
  tests++;
  I64 n = 1000000;
  U8 *big = MAlloc(n + 1);
  MemSet(big, 'a', n);
  big[n] = '\0';
  U8 *s = StrPrint(NULL, "%s%s%s%s", big, big, big, big);
  if (StrLen(s) == 4 * n && MSize(s) <= 2 * StrLen(s) + 4096) correct++;
  else "StrLen %d MSize %d\n", StrLen(s), MSize(s);
  Free(s);

  /* Many short pieces still build the right string */
  tests++;
  s = StrPrint(NULL, "%s|%s", big + n - 5, "end");
  U8 *c = NULL;
  for (i = 0; i < 1000; i++) c = CatPrint(c, "%d,", i % 10);
  if (!StrCmp(s, "aaaaa|end") && StrLen(c) == 2000 && c[1998] == '9') correct++;
  Free(s);
  Free(c);
  Free(big);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
