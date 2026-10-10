#include "testhelper.ktm"

/* FileWrite's default flags were O_CREAT|O_RDWR without O_TRUNC, so
 * writing a shorter buffer over a file kept the old tail. A negative
 * size or a NULL buffer must fail without creating the file. Everything
 * happens in a fresh mkdtemp directory. */

extern "c" U8 *mkdtemp(U8 *template);

U8 g_path[128];

Bool Exists(U8 *path)
{
  cStat st;
  return lstat(path, &st) == 0;
}

Bool Holds(U8 *path, U8 *want)
{
  I64 size = -1;
  U8 *got = FileRead(path, &size);
  Bool ok = got && size == StrLen(want) && !StrCmp(got, want);
  if (!ok && got) "got %d bytes '%s'\n", size, got;
  if (got) Free(got);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 10;
  U8 root[64], *f, *g;
  "Test - FileWrite truncates: ";

  StrCpy(root, "./ktm-test-302-XXXXXX");
  if (!mkdtemp(root)) {
    PrintResult(0, tests);
    "====\n";
    return 0;
  }
  f = StrPrint(NULL, "%s/f.txt", root);
  g = StrPrint(NULL, "%s/g.txt", root);

  if (FileWrite(f, "a long first version\n", 21)) correct++;
  if (FileWrite(f, "short\n", 6) && Holds(f, "short\n")) correct++;

  /* An empty write leaves an empty file */
  if (FileWrite(f, "", 0) && Holds(f, "")) correct++;

  /* Explicit flags still work: append */
  FileWrite(f, "ab", 2);
  if (FileWrite(f, "cd", 2, O_CREAT|O_WRONLY|O_APPEND) && Holds(f, "abcd"))
    correct++;

  /* A negative size or NULL buffer fails and creates nothing */
  if (!FileWrite(g, "xyz", -1)) correct++;
  if (!Exists(g)) correct++;
  if (!FileWrite(g, NULL, 3)) correct++;
  if (!Exists(g)) correct++;
  /* ... and leaves an existing file alone */
  if (!FileWrite(f, NULL, 0) && !FileWrite(f, "zz", -5)) correct++;
  if (Holds(f, "abcd")) correct++;

  Rm(f);
  Rm(g);
  RmDir(root);
  Free(f);
  Free(g);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
