#include "testhelper.ktm"

/* Rm marked a directory with an internal flag 0x6, which is
 * RM_VERBOSE|RM_FORCE: Rm(file, RM_VERBOSE / RM_FORCE / RM_RECURSE)
 * treated the file as a directory and did not delete it, Rm(dir)
 * printed as if RM_VERBOSE was given, and Rm(missing, RM_FORCE)
 * returned -1 although RM_FORCE means "ignore nonexistent files".
 * Everything happens in a fresh mkdtemp directory. */

extern "c" U8 *mkdtemp(U8 *template);
extern "c" I32 dup(I32 fd);
extern "c" I32 dup2(I32 fd, I32 fd2);
extern "c" I32 fflush(U0 *stream);

U8 *g_root;
U8 g_path[256];
U8 g_out[256];
I32 g_saved;

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

/* Send stdout to a file until Captured() */
U0 Capture()
{
  fflush(NULL);
  g_saved = dup(1);
  I32 fd = open(g_out, O_WRONLY|O_CREAT|O_TRUNC, 0x1A4);
  dup2(fd, 1);
  close(fd);
}

/* Restore stdout; TRUE if what was printed is `want` (with %s = path) */
Bool Captured(U8 *want, U8 *path=NULL)
{
  fflush(NULL);
  dup2(g_saved, 1);
  close(g_saved);
  U8 *got = FileRead(g_out), *exp = StrPrint(NULL, want, path);
  Bool ok = got && !StrCmp(got, exp);
  if (!ok) "printed '%s' want '%s'\n", got, exp;
  Free(got);
  Free(exp);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  U8 root[64], *full;
  I32 r;
  "Test - Rm flags: ";

  StrCpy(root, "./ktm-test-306-XXXXXX");
  if (!mkdtemp(root)) {
    PrintResult(0, 1);
    "====\n";
    return 0;
  }
  g_root = root;
  StrPrint(g_out, "%s/out.txt", root);
  MkDir(P("w"));
  MakeFile("w/a");
  MakeFile("w/b");
  MakeFile("w/c");
  MakeFile("w/d");
  MkDir(P("w/empty"));
  MkDir(P("w/empty2"));
  MkDir(P("w/full"));
  MakeFile("w/full/f");
  MkDir(P("w/full/sub"));
  MakeFile("w/full/sub/g");

  /* A file goes with any flags, quietly unless RM_VERBOSE */
  Capture;
  r = Rm(P("w/a"));
  tests++; if (Captured("") && r == 0 && !Exists("w/a")) correct++;
  Capture;
  r = Rm(P("w/b"), RM_FORCE);
  tests++; if (Captured("") && r == 0 && !Exists("w/b")) correct++;
  Capture;
  r = Rm(P("w/c"), RM_RECURSE);
  tests++; if (Captured("") && r == 0 && !Exists("w/c")) correct++;
  full = StrNew(P("w/d"));
  Capture;
  r = Rm(full, RM_VERBOSE);
  tests++; if (Captured("Rm '%s'\n", full) && r == 0 && !Exists("w/d")) correct++;
  Free(full);

  /* A missing file: an error, unless RM_FORCE */
  tests++; if (Rm(P("w/zz")) == -1) correct++;
  Capture;
  r = Rm(P("w/zz"), RM_FORCE);
  tests++; if (Captured("") && r == 0) correct++;
  tests++; if (Rm(P("w/zz"), RM_FORCE|RM_RECURSE) == 0) correct++;
  tests++; if (Rm(NULL) == -1) correct++;

  /* An empty directory goes quietly; with RM_VERBOSE it says so */
  Capture;
  r = Rm(P("w/empty"));
  tests++; if (Captured("") && r == 0 && !Exists("w/empty")) correct++;
  full = StrNew(P("w/empty2"));
  Capture;
  r = Rm(full, RM_VERBOSE);
  tests++; if (Captured("removed dir '%s'\n", full) && r == 0 &&
               !Exists("w/empty2")) correct++;
  Free(full);

  /* A full directory needs RM_RECURSE */
  Capture;
  r = Rm(P("w/full"));
  tests++; if (Captured("") && r == -1 && Exists("w/full/f")) correct++;
  tests++; if (Rm(P("w/full"), RM_FORCE) == -1 && Exists("w/full/f")) correct++;
  tests++; if (Rm(P("w/full"), RM_RECURSE) == 0 && !Exists("w/full")) correct++;

  Rm(g_out);
  tests++; if (Rm(P("w"), RM_RECURSE) == 0 && !Exists("w")) correct++;
  full = StrPrint(NULL, "rm -rf '%s'", root);
  System(full);
  Free(full);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
