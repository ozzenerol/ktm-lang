#include "testhelper.ktm"

/* RmDir(path, RM_RECURSE) without RM_VERBOSE printed "Error Rm: <file>"
 * for every file it deleted successfully: the else of
 * `if (unlink(..) == 0 && verbose)` caught the quiet successes too. It
 * now prints nothing without RM_VERBOSE and the right line with it.
 * Everything happens in a fresh mkdtemp directory. */

extern "c" U8 *mkdtemp(U8 *template);
extern "c" I32 mkfifo(U8 *path, U32 mode);
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

U0 MakeTree(U8 *top)
{
  U8 name[64];
  MkDir(P(top));
  FileWrite(P(StrPrint(name, "%s/f1", top)), "k\n", 2);
  mkfifo(P(StrPrint(name, "%s/fifo", top)), 0x1A4);
  MkDir(P(StrPrint(name, "%s/sub", top)));
  FileWrite(P(StrPrint(name, "%s/sub/f2", top)), "k\n", 2);
}

U0 Capture()
{
  fflush(NULL);
  g_saved = dup(1);
  I32 fd = open(g_out, O_WRONLY|O_CREAT|O_TRUNC, 0x1A4);
  dup2(fd, 1);
  close(fd);
}

/* Restore stdout and return what was printed */
U8 *Captured()
{
  fflush(NULL);
  dup2(g_saved, 1);
  close(g_saved);
  U8 *got = FileRead(g_out);
  Rm(g_out);
  return got;
}

/* `out` has the line `fmt` (with %s = the path of `name`) */
Bool HasLine(U8 *out, U8 *fmt, U8 *name)
{
  U8 line[512];
  StrPrint(line, fmt, P(name));
  return out && strstr(out, line) != NULL;
}

I32 Main()
{
  I64 correct = 0, tests = 6;
  U8 root[64], *out;
  I32 r;
  "Test - RmDir quiet without RM_VERBOSE: ";

  StrCpy(root, "./ktm-test-307-XXXXXX");
  if (!mkdtemp(root)) {
    PrintResult(0, tests);
    "====\n";
    return 0;
  }
  g_root = root;
  StrPrint(g_out, "%s/out.txt", root);

  MakeTree("q");
  Capture;
  r = RmDir(P("q"), RM_RECURSE);
  out = Captured;
  if (r == 0 && !Exists("q")) correct++;
  if (out && !StrCmp(out, "")) correct++;
  else "printed '%s'\n", out;
  Free(out);

  MakeTree("v");
  Capture;
  r = RmDir(P("v"), RM_RECURSE|RM_VERBOSE);
  out = Captured;
  if (r == 0 && !Exists("v")) correct++;
  if (HasLine(out, "Rm: %s\n", "v/f1") && HasLine(out, "Rm: %s\n", "v/fifo") &&
      HasLine(out, "Rm: %s\n", "v/sub/f2")) correct++;
  if (HasLine(out, "RmDir %s\n", "v/sub") && HasLine(out, "RmDir %s\n", "v"))
    correct++;
  if (out && !strstr(out, "Error") && !strstr(out, "ERROR")) correct++;
  else "printed '%s'\n", out;
  Free(out);

  out = StrPrint(NULL, "rm -rf '%s'", root);
  System(out);
  Free(out);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
