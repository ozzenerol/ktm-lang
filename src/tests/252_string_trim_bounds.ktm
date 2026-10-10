#include "testhelper.ktm"

/* StrTrimEnd on an empty or all-blank string walked back past the start
 * and wrote the NUL before it; StrTrimStart ignored its str_len. Both now
 * stay inside [0, len) and only look at the first str_len bytes when it
 * is given; NULL returns NULL with *_new_len = 0. */

Bool TrimEnd(U8 *in, I64 str_len, U8 *want, I64 want_len)
{
  U8 buf[64];
  buf[0] = '#';                 /* guard byte before the string */
  StrCpy(buf + 1, in);
  I64 n = -5;
  U8 *r = StrTrimEnd(buf + 1, str_len, &n);
  Bool ok = r == buf + 1 && n == want_len && !StrNCmp(buf + 1, want, want_len) &&
            buf[0] == '#';
  if (ok && want_len != StrLen(in)) ok = buf[1 + want_len] == '\0';
  if (!ok) "TrimEnd(\"%s\", %d) -> \"%s\" %d\n", in, str_len, buf + 1, n;
  return ok;
}

Bool TrimStart(U8 *in, I64 str_len, U8 *want, I64 want_len)
{
  U8 buf[64];
  StrCpy(buf, in);
  I64 n = -5;
  U8 *r = StrTrimStart(buf, str_len, &n);
  Bool ok = r == buf && n == want_len && !StrNCmp(buf, want, want_len);
  if (!ok) "TrimStart(\"%s\", %d) -> \"%s\" %d\n", in, str_len, buf, n;
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - string trim bounds: ";

  Bool ok = TrimEnd("", -1, "", 0) && TrimEnd("   ", -1, "", 0) &&
            TrimEnd(" \t\n", -1, "", 0) && TrimEnd("ab  ", -1, "ab", 2) &&
            TrimEnd("ab", -1, "ab", 2) && TrimEnd("  ab", -1, "  ab", 4) &&
            TrimEnd("ab   cd", 4, "ab", 2) && TrimEnd("  x", 2, "", 0);
  tests++; if (ok) correct++;

  /* the audit's repro: an all-blank string inside a buffer */
  U8 big[16];
  StrCpy(big, "x    ");
  I64 len = 99;
  StrTrimEnd(big + 3, -1, &len);
  tests++; if (len == 0 && !StrCmp(big, "x  ")) correct++;

  ok = TrimStart("", -1, "", 0) && TrimStart("   ", -1, "", 0) &&
       TrimStart("  ab", -1, "ab", 2) && TrimStart("ab", -1, "ab", 2) &&
       TrimStart("ab  ", -1, "ab  ", 4);
  tests++; if (ok) correct++;

  /* str_len honoured: only the first str_len bytes count */
  U8 b2[32];
  StrCpy(b2, "   abc   def");
  len = 0;
  StrTrimStart(b2, 6, &len);   /* "   abc" -> "abc" */
  tests++; if (len == 3 && !StrCmp(b2, "abc")) correct++;
  else "str_len ignored: \"%s\" %d\n", b2, len;
  StrCpy(b2, "      xyz");
  StrTrimStart(b2, 4, &len);   /* all blank within the first 4 */
  tests++; if (len == 0 && b2[0] == '\0') correct++;

  len = 7;
  tests++;
  if (StrTrimStart(NULL, -1, &len) == NULL && len == 0 &&
      StrTrimEnd(NULL) == NULL) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
