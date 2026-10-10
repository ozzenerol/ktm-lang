#include "testhelper.ktm"

/* Rm(link, RM_RECURSE) on a symlink to a directory stat()ed the target,
 * then emptied the directory the link points to. A symlink inside a
 * tree was fine, but Rm(link) and Rm(dangling link) failed. Everything
 * here happens in a fresh mkdtemp directory. */

extern "c" U8 *mkdtemp(U8 *template);
extern "c" I32 symlink(U8 *target, U8 *linkpath);

U8 *g_root;
U8 g_path[512];

U8 *P(U8 *name)
{
  StrPrint(g_path, "%s/%s", g_root, name);
  return g_path;
}

Bool Exists(U8 *name)
{
  cStat st;
  return lstat(P(name), &st) == 0;
}

U0 MakeFile(U8 *name)
{
  FileWrite(P(name), "k\n", 2);
}

I32 Main()
{
  I64 correct = 0, tests = 13;
  U8 root[64];
  "Test - Rm does not follow symlinks: ";

  StrCpy(root, "./ktm-test-300-XXXXXX");
  if (!mkdtemp(root)) {
    PrintResult(0, tests);
    "====\n";
    return 0;
  }
  g_root = root;

  MkDir(P("target"));
  MakeFile("target/keep1");
  MakeFile("target/keep2");
  MkDir(P("tree"));
  MakeFile("tree/f");
  symlink("../target", P("tree/inner"));
  symlink("target", P("link"));
  symlink("target", P("link2"));
  symlink("target", P("link3"));
  symlink("nowhere", P("dangling"));

  /* The link goes, the directory it points to stays full */
  if (Rm(P("link"), RM_RECURSE) == 0) correct++;
  if (!Exists("link")) correct++;
  if (Exists("target/keep1") && Exists("target/keep2")) correct++;

  /* Without flags too */
  if (Rm(P("link2")) == 0) correct++;
  if (!Exists("link2") && Exists("target/keep1")) correct++;

  /* A dangling link can be removed */
  if (Rm(P("dangling")) == 0) correct++;
  if (!Exists("dangling")) correct++;

  /* A link inside a tree is removed, not followed */
  if (Rm(P("tree"), RM_RECURSE) == 0) correct++;
  if (!Exists("tree")) correct++;
  if (Exists("target/keep1") && Exists("target/keep2")) correct++;

  /* RmDir refuses a symlink: it is not a directory */
  if (RmDir(P("link3"), RM_RECURSE) == -1) correct++;
  if (Exists("link3") && Exists("target/keep1")) correct++;

  /* The real directory still goes */
  Rm(P("link3"));
  if (Rm(P("target"), RM_RECURSE) == 0 && !Exists("target")) correct++;

  U8 *cmd = StrPrint(NULL, "rm -rf '%s'", root);
  System(cmd);
  Free(cmd);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
