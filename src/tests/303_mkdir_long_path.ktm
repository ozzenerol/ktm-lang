#include "testhelper.ktm"

/* MkDir(path,,TRUE) copied each prefix of path into a DIR_MAXPATHLEN
 * (256 on Linux, 1024 on macOS) stack buffer without a bound, so a
 * longer path smashed the stack. Everything happens in a fresh mkdtemp
 * directory. The path stays under macOS's PATH_MAX of 1024. */

extern "c" U8 *mkdtemp(U8 *template);

Bool IsDir(U8 *p)
{
  cStat st;
  return stat(p, &st) == 0 && (st.st_mode & S_IFMT) == S_IFDIR;
}

I32 Main()
{
  I64 correct = 0, tests = 4, i;
  U8 root[64], *p, *q;
  "Test - MkDir recursive on a long path: ";

  StrCpy(root, "./ktm-test-303-XXXXXX");
  if (!mkdtemp(root)) {
    PrintResult(0, tests);
    "====\n";
    return 0;
  }

  /* 18 levels of 48 characters: ~900 bytes */
  p = StrNew(root);
  for (i = 0; i < 18; i++) {
    p = CatPrint(p, "/%s", "dddddddddddddddddddddddddddddddddddddddddddddddd");
  }
  if (StrLen(p) > 800 && StrLen(p) < 1000) correct++;
  MkDir(p,, TRUE);
  if (IsDir(p)) correct++;

  /* Again with a trailing '/', over the existing directories */
  q = StrPrint(NULL, "%s/e/", p);
  MkDir(q,, TRUE);
  if (IsDir(q)) correct++;

  /* A short one still works */
  Free(q);
  q = StrPrint(NULL, "%s/x/y/z", root);
  MkDir(q,, TRUE);
  if (IsDir(q)) correct++;

  Free(q);
  Free(p);
  q = StrPrint(NULL, "rm -rf '%s'", root);
  System(q);
  Free(q);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
