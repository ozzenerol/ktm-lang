#include "testhelper.ktm"

/* MPrintQ (and %Q) wrote a control byte as `x01`, with no backslash: the
 * result could not be told from the text "x01" and did not read back as
 * the same string. It is now `\x01`, which a ktm string literal reads
 * back as the byte (ktm's `\x` takes at most two hex digits). */

#define PQ_SRC "./ktm-248-pq.ktm"
#define PQ_OUT "./ktm-248-pq.out"

Bool Is(U8 *got, U8 *want)
{
  Bool ok = got && !StrCmp(got, want);
  if (!ok) "got [%s] want [%s]\n", got, want;
  Free(got);
  return ok;
}

I32 Main()
{
  "Test - MPrintQ control escapes: ";
  I64 correct = 0, tests = 0, i;

  tests++;
  if (Is(MPrintQ("a\x01"), "a\\x01") && Is(MPrintQ("ax01"), "ax01")) correct++;
  tests++;
  if (Is(MPrintQ("\x1b[0m\x7f"), "\\x1B[0m\\x7F")) correct++;
  tests++;
  if (Is(MPrintQ("tab\tnl\ncr\rq\"bs\\"), "tab\\tnl\\ncr\\rq\\\"bs\\\\")) correct++;
  /* A hex digit after the escape stays a separate character */
  tests++;
  if (Is(MPrintQ("\x01" "abc"), "\\x01abc")) correct++;
  tests++;
  if (Is(StrPrint(NULL, "[%Q]", "\x02z"), "[\\x02z]")) correct++;

  /* Every byte 1..255 reads back the same through a string literal */
  tests++;
  U8 all[256];
  for (i = 1; i < 256; i++) all[i - 1] = i;
  all[255] = '\0';
  U8 *q = MPrintQ(all);
  U8 *src = StrPrint(NULL, "U0 Main() { \"%%s\", \"%s\"; }\n", q);
  System("rm -f " PQ_SRC " " PQ_OUT);
  FileWrite(PQ_SRC, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-jit " PQ_SRC " > " PQ_OUT);
  I64 len = 0;
  U8 *back = FileRead(PQ_OUT, &len);
  if (back && len == 255 && !StrCmp(back, all)) correct++;
  else "read back %d bytes\n", len;
  Free(back);
  Free(src);
  Free(q);
  System("rm -f " PQ_SRC " " PQ_OUT);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
