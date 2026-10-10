#include "testhelper.ktm"

/* StrPrint ignored the width of %s and used its precision as the width:
 * "[%5s][%-5s][%.2s]" gave "[ab][ab][abcd]". Right-justified padding was
 * also written through a copy of the destination pointer and then
 * overwritten by the string, and %-8d/%-8x padded on the left. Every case
 * here must print exactly what C's printf prints. */

extern "c" I64 snprintf(U8 *buf, U64 len, U8 *fmt, ...);

I64 g_bad = 0;

U0 Same(U8 *fmt, U8 *ours, U8 *want)
{
  if (StrCmp(ours, want)) {
    g_bad++;
    "\n  %s: got '%s', C printf gives '%s'", fmt, ours, want;
  }
}

U0 CheckS(U8 *fmt, U8 *s)
{
  U8 ours[512], want[512];
  StrPrint(ours, fmt, s);
  snprintf(want, sizeof(want), fmt, s);
  Same(fmt, ours, want);
}

U0 CheckI(U8 *fmt, I64 v)
{
  U8 ours[512], want[512];
  StrPrint(ours, fmt, v);
  snprintf(want, sizeof(want), fmt, v);
  Same(fmt, ours, want);
}

U0 CheckStar(U8 *fmt, I64 a, I64 b, U8 *s)
{
  U8 ours[512], want[512];
  StrPrint(ours, fmt, a, b, s);
  snprintf(want, sizeof(want), fmt, a, b, s);
  Same(fmt, ours, want);
}

I32 Main()
{
  I64 correct = 0, tests = 14, i, j;
  U8 *sfmts[13] = {
    "[%s]", "[%5s]", "[%-5s]", "[%.2s]", "[%5.2s]", "[%-5.2s]", "[%.0s]",
    "[%.10s]", "[%1s]", "[%12s]", "[%-12s]", "[%3.1s]", "[%-1.3s]"
  };
  U8 *strs[5] = { "", "a", "ab", "abcd", "hello world" };
  U8 *ifmts[15] = {
    "[%d]", "[%8d]", "[%-8d]", "[%08d]", "[%-08d]", "[%1d]", "[%u]",
    "[%8u]", "[%-8u]", "[%x]", "[%8x]", "[%-8x]", "[%08x]", "[%X]",
    "[%-6X]"
  };
  I64 ivals[5] = { 0, 7, 42, 123456789, 0x7fffffff };
  I64 nvals[3] = { -1, -42, -123456 };
  U8 buf[64], ours[2048], want[2048], *m, *c;

  "Test - StrPrint width, '-' and precision of %%s match C printf: ";

  /* 1: %s width, '-' and precision */
  for (i = 0; i < 5; ++i)
    for (j = 0; j < 13; ++j)
      CheckS(sfmts[j], strs[i]);
  if (!g_bad) correct++;

  /* 2: %d %u %x %X width, '-' and '0' */
  g_bad = 0;
  for (i = 0; i < 5; ++i)
    for (j = 0; j < 15; ++j)
      CheckI(ifmts[j], ivals[i]);
  for (i = 0; i < 3; ++i)
    for (j = 0; j < 6; ++j)
      CheckI(ifmts[j], nvals[i]);
  if (!g_bad) correct++;

  /* 3: '*' width and precision, a negative '*' width left-justifies */
  g_bad = 0;
  CheckStar("[%*.*s]", 6, 2, "abcd");
  CheckStar("[%-*.*s]", 6, 3, "abcd");
  CheckStar("[%*.*s]", -6, 1, "abcd");
  CheckStar("[%*.*s]", 2, 10, "abcd");
  CheckStar("[%*.*s]", 0, 0, "abcd");
  CheckStar("[%*.*s]", 3, -1, "abcd"); /* negative precision: none */
  if (!g_bad) correct++;

  /* 4: the bug report, into a caller's stack buffer */
  if (!StrCmp(StrPrint(buf, "[%5s][%-5s][%.2s]", "ab", "ab", "abcd"),
              "[   ab][ab   ][ab]")) correct++;

  /* 5: into a buffer in the middle of an allocation, which is never grown */
  U8 *mid = CAlloc(128);
  if (!StrCmp(StrPrint(mid + 16, "%8s|%-8s|%.1s|%-6d|%6d", "x", "y", "zz", 7, -7),
              "       x|y       |z|7     |    -7")) correct++;
  Free(mid);

  /* 6: StrPrint(NULL, ...) grows its buffer past STR_LEN while padding */
  m = StrPrint(NULL, "%300s|%-300s|%.3s", "right", "left", "truncated");
  snprintf(want, sizeof(want), "%300s|%-300s|%.3s", "right", "left", "truncated");
  if (!StrCmp(m, want)) correct++;
  Free(m);

  /* 7: MStrPrint, several regrowths, padding straddles each one */
  m = MStrPrint("%255s%-257s%511s%d", "a", "b", "c", 42);
  snprintf(want, sizeof(want), "%255s%-257s%511s%d", "a", "b", "c", 42);
  if (!StrCmp(m, want) && StrLen(m) == 255 + 257 + 511 + 2) correct++;
  Free(m);

  /* 8: CatPrint */
  c = MStrPrint("[%3s]", "a");
  m = CatPrint(c, "[%-4s][%4.2s]", "bc", "def"); /* takes over c */
  if (!StrCmp(m, "[  a][bc  ][  de]")) correct++;
  Free(m);

  /* 9: a NULL string prints nothing, but still takes its width */
  if (!StrCmp(StrPrint(buf, "[%s][%4s][%-4s][%.2s]", NULL, NULL, NULL, NULL),
              "[][    ][    ][]")) correct++;

  /* 10: %c and %C take a width and '-' too */
  StrPrint(ours, "[%3c][%-3c][%c]", 'a', 'b', 'c');
  snprintf(want, sizeof(want), "[%3c][%-3c][%c]", 'a', 'b', 'c');
  if (!StrCmp(ours, want) &&
      !StrCmp(StrPrint(buf, "[%4C][%-4C]", 'ab', 'cd'), "[  AB][CD  ]")) {
    correct++;
  }

  /* 11, 12: %Q and %p with widths */
  if (!StrCmp(StrPrint(buf, "[%6Q][%-6Q]", "a\"b", "a\nb"),
              "[  a\\\"b][a\\nb  ]")) correct++;
  if (!StrCmp(StrPrint(buf, "[%p]", 0x1234), "[0x0000000000001234]") &&
      !StrCmp(StrPrint(buf, "[%20p][%-20p]", 0xab, 0xab),
              "[  0x00000000000000AB][0x00000000000000AB  ]")) correct++;

  /* 13: %D and %T with widths */
  CDate cdt = Now();
  U8 *d = MPrintDate(cdt), *t = MPrintTime(cdt);
  snprintf(want, sizeof(want), "[%12s][%-12s][%10s][%-10s]", d, d, t, t);
  StrPrint(ours, "[%12D][%-12D][%10T][%-10T]", cdt, cdt, cdt, cdt);
  if (!StrCmp(ours, want)) correct++;
  Free(d);
  Free(t);

  /* 14: floats and mixed formats are unchanged */
  StrPrint(ours, "%-6s=%8.3f|%5s|%-3d|%%|%s", "pi", 3.14159, "ok", 9, "end");
  snprintf(want, sizeof(want), "%-6s=%8.3f|%5s|%-3d|%%|%s",
           "pi", 3.14159, "ok", 9, "end");
  if (!StrCmp(ours, want)) correct++;
  else "\n  mixed: got '%s', C printf gives '%s'", ours, want;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
