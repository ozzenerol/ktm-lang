#include "testhelper.ktm"

/* MkDir's result could not tell success from failure: without recurse
 * it returned 0 on success and also for a missing parent or a read-only
 * directory, -1 only when the path was a file; with recurse it returned
 * TRUE/FALSE and ignored whether the last directory was made. It now
 * returns 0 if the path is a directory afterwards and -1 if not, like
 * Rm and RmDir. Everything happens in a fresh mkdtemp directory. */

extern "c" U8 *mkdtemp(U8 *template);
extern "c" I32 chmod(U8 *path, U32 mode);
extern "c" U32 getuid();

U8 *g_root;
U8 g_path[256];

U8 *P(U8 *name)
{
  StrPrint(g_path, "%s/%s", g_root, name);
  return g_path;
}

Bool IsDir(U8 *name)
{
  cStat st;
  return stat(P(name), &st) == 0 && (st.st_mode & S_IFMT) == S_IFDIR;
}

I64 g_correct = 0, g_tests = 0;

U0 Expect(U8 *name, Bool recurse, I64 want)
{
  I64 got = MkDir(P(name),, recurse);
  g_tests++;
  if (got == want && IsDir(name) == (want == 0)) g_correct++;
  else "MkDir(%s, recurse=%d) -> %d, want %d\n", name, recurse, got, want;
}

I32 Main()
{
  U8 root[64], *cmd;
  "Test - MkDir return value: ";

  StrCpy(root, "./ktm-test-309-XXXXXX");
  if (!mkdtemp(root)) {
    PrintResult(0, 1);
    "====\n";
    return 0;
  }
  g_root = root;
  FileWrite(P("file"), "k\n", 2);

  Expect("new", FALSE, 0);
  Expect("new", FALSE, 0);          /* already there */
  Expect("file", FALSE, -1);        /* a file is in the way */
  Expect("no/such", FALSE, -1);     /* missing parent */
  Expect("a/b/c", TRUE, 0);
  Expect("a/b/c", TRUE, 0);         /* already there */
  Expect("t1/t2/", TRUE, 0);        /* trailing '/' */
  Expect("file/x/y", TRUE, -1);     /* a file in the middle */
  Expect("a/file2/", TRUE, 0);
  FileWrite(P("a/f3"), "k\n", 2);
  Expect("a/f3", TRUE, -1);         /* a file at the end */

  /* Permissions don't stop root */
  if (getuid() != 0) {
    MkDir(P("ro"));
    chmod(P("ro"), 0x16D);          /* 0555 */
    Expect("ro/x", FALSE, -1);
    Expect("ro/y", TRUE, -1);
    Expect("ro/y/z", TRUE, -1);
    chmod(P("ro"), 0x1ED);          /* 0755 */
  }

  g_tests++; if (MkDir(NULL) == -1) g_correct++;
  g_tests++; if (MkDir("") == -1 && MkDir("",, TRUE) == -1) g_correct++;

  cmd = StrPrint(NULL, "rm -rf '%s'", root);
  System(cmd);
  Free(cmd);

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
