#include "testhelper.ktm"

/* Dir(NULL) and RmDir(NULL, RM_RECURSE) passed NULL to opendir() and
 * segfaulted. Both return -1 now; Dir also returns -1 (not 0, which is
 * an empty directory) for a path it can't open as a directory.
 * Everything happens in a fresh mkdtemp directory. */

extern "c" U8 *mkdtemp(U8 *template);

I32 Main()
{
  I64 correct = 0, tests = 7;
  U8 root[64], *f, *cmd;
  "Test - Dir and RmDir with NULL: ";

  if (Dir(NULL) == -1) correct++;
  if (RmDir(NULL, RM_RECURSE) == -1) correct++;
  if (RmDir(NULL) == -1 && RmDir(NULL, RM_RECURSE|RM_VERBOSE) == -1) correct++;
  if (Rm(NULL) == -1) correct++;

  StrCpy(root, "./ktm-test-314-XXXXXX");
  if (mkdtemp(root)) {
    f = StrPrint(NULL, "%s/f", root);
    FileWrite(f, "k\n", 2);
    if (Dir(f) == -1) correct++;
    Free(f);
    f = StrPrint(NULL, "%s/empty", root);
    MkDir(f);
    if (Dir(f) == 0) correct++;  /* an empty directory is not an error */
    Free(f);
    f = StrPrint(NULL, "%s/missing", root);
    if (Dir(f) == -1) correct++;
    Free(f);
    cmd = StrPrint(NULL, "rm -rf '%s'", root);
    System(cmd);
    Free(cmd);
  }

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
