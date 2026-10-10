#include "testhelper.ktm"

/* On arm64 Linux cStat had x86_64's struct stat layout, so st_mode,
 * st_size and the times were read from the wrong offsets: a regular file
 * didn't look regular and FileRead, Rm and MkDir misbehaved. */

extern "c" U8 *mkdtemp(U8 *template);

I32 Main()
{
  I64 correct = 0, tests = 0;
  U8 root[64], *f, *d, *cmd;
  cStat st;
  "Test - cStat layout: ";

  StrCpy(root, "./ktm-test-293-XXXXXX");
  if (!mkdtemp(root)) {
    PrintResult(0, 1);
    "====\n";
    return 0;
  }
  f = StrPrint(NULL, "%s/file", root);
  d = StrPrint(NULL, "%s/dir", root);
  FileWrite(f, "hello world\n", 12);
  MkDir(d);

  tests++;
  if (stat(f, &st) == 0 && (st.st_mode & S_IFMT) == S_IFREG) correct++;
  tests++;
  if (st.st_size == 12) correct++;
  tests++;
  if ((st.st_mode & 0x1FF) != 0 && st.st_nlink == 1) correct++;

  tests++;
  if (stat(d, &st) == 0 && (st.st_mode & S_IFMT) == S_IFDIR) correct++;

  tests++;
  I32 fd = open(f, O_RDONLY);
  if (fd >= 0 && fstat(fd, &st) == 0 && (st.st_mode & S_IFMT) == S_IFREG &&
      st.st_size == 12) correct++;
  if (fd >= 0) close(fd);

  tests++;
  I64 len = -1;
  U8 *buf = FileRead(f, &len);
  if (buf && len == 12 && !StrNCmp(buf, "hello world\n", 12)) correct++;
  Free(buf);

#ifdef IS_LINUX
  tests++;
#ifdef IS_ARM_64
  if (sizeof(cStat) == 128) correct++;
#else
  if (sizeof(cStat) == 144) correct++;
#endif
#endif

  cmd = StrPrint(NULL, "rm -rf '%s'", root);
  System(cmd);
  Free(cmd);
  Free(f);
  Free(d);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
