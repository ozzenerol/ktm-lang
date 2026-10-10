#include "testhelper.ktm"

/* FileRead asked read() for the whole file length on every call, even
 * after the buffer was full, so the EOF probe claimed bytes past the end
 * of the buffer. A real kernel returns 0 without looking, but qemu-user
 * checks the range and fails with EFAULT when it crosses the end of the
 * heap, and FileRead then returned NULL. That only shows under qemu with
 * an unlucky heap layout (39_arg_count.ktm under the arm64 JIT caught it);
 * this test pins down that the bounded read loop returns every byte. */

#define FILEREAD_TEST_FILE "__120_fileread.bin"

Bool ReadBackMatches(I64 len)
{
  U8 *buf = MAlloc(len + 1);
  for (I64 i = 0; i < len; i++) {
    buf[i] = 'a' + i % 26;
  }
  buf[len] = '\0';
  if (!FileWrite(FILEREAD_TEST_FILE, buf, len, O_CREAT | O_RDWR | O_TRUNC)) {
    Free(buf);
    return FALSE;
  }

  I64 size = -1;
  U8 *got = FileRead(FILEREAD_TEST_FILE, &size);
  Bool ok = got != NULL && size == len && memcmp(got, buf, len) == 0 &&
            got[len] == '\0';
  if (!ok) {
    "FileRead of %d bytes: got %p, size %d\n", len, got, size;
  }
  Free(got);
  Free(buf);
  Rm(FILEREAD_TEST_FILE);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  I64 sizes[6] = {0, 1, 99, 4096, 4097, 1 << 20};
  "Test - FileRead returns the whole file: ";
  for (I64 i = 0; i < 6; i++) {
    tests++;
    if (ReadBackMatches(sizes[i])) correct++;
  }
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
