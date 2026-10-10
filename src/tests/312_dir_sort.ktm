#include "testhelper.ktm"

/* Dir sorted names that start with a number by that number only: names
 * with the same number came out in any order, a number too big for an
 * I64 overflowed, and its own quicksort went quadratic (and n deep) on
 * equal keys. Entries whose Stat failed, like a dangling symlink, were
 * left out. Everything happens in a fresh mkdtemp directory. */

extern "c" U8 *mkdtemp(U8 *template);
extern "c" I32 symlink(U8 *target, U8 *linkpath);
extern "c" I32 dup(I32 fd);
extern "c" I32 dup2(I32 fd, I32 fd2);
extern "c" I32 fflush(U0 *stream);

U8 g_out[256];
I32 g_saved;

U0 Capture()
{
  fflush(NULL);
  g_saved = dup(1);
  I32 fd = open(g_out, O_WRONLY|O_CREAT|O_TRUNC, 0x1A4);
  dup2(fd, 1);
  close(fd);
}

U8 *Captured()
{
  fflush(NULL);
  dup2(g_saved, 1);
  close(g_saved);
  U8 *got = FileRead(g_out);
  Rm(g_out);
  return got;
}

U0 Touch(U8 *dir, U8 *name)
{
  U8 *p = StrPrint(NULL, "%s/%s", dir, name);
  FileWrite(p, "", 0);
  Free(p);
}

I32 Main()
{
  I64 correct = 0, tests = 6, n, i;
  U8 root[64], *d, *big, *out, *p, name[32];
  U8 *names[17] = {"7_j", "7_c", "7_a", "7_h", "7_e", "7_b", "7_i", "7_d",
                   "7_g", "7_f", "18446744073709551617", "3", "2.5", "2.10",
                   "02", "B", "a"};
  "Test - Dir sort order: ";

  StrCpy(root, "./ktm-test-312-XXXXXX");
  if (!mkdtemp(root)) {
    PrintResult(0, tests);
    "====\n";
    return 0;
  }
  StrPrint(g_out, "%s/out.txt", root);
  d = StrPrint(NULL, "%s/d", root);
  big = StrPrint(NULL, "%s/big", root);
  MkDir(d);
  MkDir(big);
  for (i = 0; i < 17; i++) Touch(d, names[i]);
  p = StrPrint(NULL, "%s/dangling", d);
  symlink("nowhere", p);
  Free(p);

  Capture;
  n = Dir(d);
  out = Captured;
  if (n == 18) correct++;
  else "count %d\n", n;
  if (out && !StrCmp(out,
      "02\n2.10\n2.5\n3\n7_a\n7_b\n7_c\n7_d\n7_e\n7_f\n7_g\n7_h\n7_i\n7_j\n"
      "18446744073709551617\nB\na\ndangling\n")) correct++;
  else "got:\n%s\n", out;
  Free(out);

  /* Many names with the same leading number */
  for (i = 0; i < 3000; i++) {
    StrPrint(name, "1_%d", i);
    Touch(big, name);
  }
  Capture;
  n = Dir(big);
  out = Captured;
  if (n == 3000) correct++;
  if (out && !StrNCmp(out, "1_0\n1_1\n1_10\n1_100\n1_1000\n1_1001\n", 33))
    correct++;
  if (out && StrLen(out) > 6 && !StrCmp(out + StrLen(out) - 6, "\n1_999\n"+1))
    correct++;
  Free(out);

  /* An empty directory */
  p = StrPrint(NULL, "%s/empty", root);
  MkDir(p);
  Capture;
  n = Dir(p);
  out = Captured;
  if (n == 0 && out && !StrCmp(out, "")) correct++;
  Free(out);
  Free(p);

  Free(d);
  Free(big);
  p = StrPrint(NULL, "rm -rf '%s'", root);
  System(p);
  Free(p);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
