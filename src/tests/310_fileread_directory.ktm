#include "testhelper.ktm"

/* FileRead on a directory took lseek(SEEK_END) as the size, which is
 * LLONG_MAX on ext4: MAlloc(len+100) overflowed and it crashed. Only a
 * regular file is read now; anything else gives NULL. *_size is set on
 * every return, 0 on failure. Everything happens in a fresh mkdtemp
 * directory. */

extern "c" U8 *mkdtemp(U8 *template);

I32 Main()
{
  I64 correct = 0, tests = 7, n;
  U8 root[64], *s, *f, *cmd;
  "Test - FileRead on a directory: ";

  MemCpy(root, "/tmp/ktm-test-310-XXXXXX", 25);
  if (!mkdtemp(root)) {
    PrintResult(0, tests);
    "====\n";
    return 0;
  }
  f = StrPrint(NULL, "%s/f.txt", root);

  n = 77;
  s = FileRead(root, &n);
  if (s == NULL && n == 0) correct++;
  if (FileRead(root) == NULL) correct++;
  /* The working directory: /tmp may be tmpfs, this is often ext4 */
  n = 77;
  if (FileRead(".", &n) == NULL && n == 0) correct++;

  /* Other failures set the size too */
  n = 77;
  if (FileRead(f, &n) == NULL && n == 0) correct++;
  n = 77;
  if (FileRead(NULL, &n) == NULL && n == 0) correct++;

  /* Regular files, empty or not, still read */
  FileWrite(f, "", 0);
  n = 77;
  s = FileRead(f, &n);
  if (s && n == 0 && s[0] == '\0') correct++;
  Free(s);
  FileWrite(f, "hello\n", 6);
  s = FileRead(f, &n);
  if (s && n == 6 && !StrCmp(s, "hello\n")) correct++;
  Free(s);

  Free(f);
  cmd = StrPrint(NULL, "rm -rf '%s'", root);
  System(cmd);
  Free(cmd);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
