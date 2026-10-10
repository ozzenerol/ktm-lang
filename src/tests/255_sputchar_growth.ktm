#include "testhelper.ktm"

/* SPutChar grew a full buffer with MAlloc(i<<1+1), which ktm parses as
 * i<<(1+1): from an empty 1-byte buffer (i=0) it allocated 0 bytes and
 * wrote the char and its NUL past the block. It now always has room for
 * the char and the NUL (valgrind checks the AOT binary). */

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - SPutChar growth: ";

  /* from a 1-byte buffer, one char at a time */
  U8 *buf = MAlloc(1), *dst = buf;
  buf[0] = '\0';
  Bool ok = TRUE;
  for (I64 i = 0; i < 1000; ++i) {
    SPutChar(&dst, 'a' + i % 26, &buf);
    if (dst - buf != i + 1 || MSize(buf) < i + 2 || *dst != '\0') ok = FALSE;
  }
  for (I64 i = 0; i < 1000; ++i) if (buf[i] != 'a' + i % 26) ok = FALSE;
  tests++; if (ok && StrLen(buf) == 1000) correct++;
  Free(buf);

  /* from buffers of every small size, and with a buffer that has room */
  ok = TRUE;
  for (I64 size = 1; size < 10; ++size) {
    buf = MAlloc(size);
    buf[0] = '\0';
    dst = buf;
    for (I64 i = 0; i < 20; ++i) SPutChar(&dst, 'x', &buf);
    if (StrLen(buf) != 20 || dst != buf + 20) ok = FALSE;
    Free(buf);
  }
  tests++; if (ok) correct++;

  /* without _buf it just writes */
  U8 fixed[8];
  dst = fixed;
  SPutChar(&dst, 'h', NULL);
  SPutChar(&dst, 'i', NULL);
  tests++; if (!StrCmp(fixed, "hi")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
