#include "testhelper.ktm"

/* `-transpile` wrote array sizes in bytes (`Leaf leaves[2];` became
 * `Leaf leaves[32];`), kept only one dimension of `I32 grid[2][3]`, and
 * wrote `a[i]` as `*(a[i])` (so `b.leaves[1].a` became
 * `*(b.leaves[1]).a`) and `*p` as `**p`. Field access on `(*p)` and
 * `(p + 1)` also lost its brackets. */

#define TA_FILE "__211_transpile.ktm"
#define TA_C    "__211_transpile.c"
#define TA_EXE  "__211_transpile.exe"
#define TA_OUT  "__211_transpile.out"
/* `timeout` is coreutils; without it the command just runs */
#define TA_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "

U0 CleanupTranspileArrTest(U0)
{
  System("rm -f " TA_FILE " " TA_C " " TA_EXE " " TA_OUT);
}

U8 *ta_source =
"class Leaf
{
  I64 a;
  I32 b;
};

class Branch
{
  Leaf leaves[2];
  I64 nums[3];
  I32 grid[2][3];
  U8 name[8];
  Branch *next;
};

U0 Main()
{
  Branch b;
  Branch *p = &b;
  Branch **pp = &p;
  I64 loc[4];
  I16 cube[2][3][4];
  I64 *q = loc;
  U8 *s = \"hey\";

  b.leaves[1].a = 5;
  b.leaves[0].b = 6;
  b.nums[2] = 7;
  b.grid[1][2] = 8;
  b.name[0] = 'h';
  b.name[1] = 0;
  cube[1][2][3] = 9;
  loc[3] = 10;
  p->leaves[1].b = 11;
  *q = 12;
  *(q + 1) = 13;
  (*p).nums[0] = 14;
  (*pp)->nums[1] = 15;
  b.next = p;
  b.next->leaves[0].a = 16;
  \"%d %d %d %d %s %d %d %d\\n\", b.leaves[1].a, b.leaves[0].b, b.nums[2],
    b.grid[1][2], b.name, cube[1][2][3], loc[3], p->leaves[1].b;
  \"%d %d %d %d %d %c %d\\n\", loc[0], q[1], b.nums[0], (*pp)->nums[1],
    p->next->leaves[0].a, *(s + 1), sizeof(Branch);
}
";

U8 *ta_expected = "5 6 7 8 h 9 10 11\n12 13 14 15 16 e 96\n";

U0 Main()
{
  "Test - transpile array members: ";
  I64 correct = 0, tests = 0, res;
  U8 *code, *out;

  CleanupTranspileArrTest;
  FileWrite(TA_FILE, ta_source, StrLen(ta_source), O_CREAT | O_RDWR | O_TRUNC);

  /* What the JIT prints, to compare the C program with */
  tests++;
  res = System(TA_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " TA_FILE " > " TA_OUT " 2>&1");
  out = FileRead(TA_OUT);
  if (res == 0 && out && !StrCmp(out, ta_expected)) correct++;
  else if (out) "jit: %s\n", out;
  if (out) Free(out);

  res = System(TA_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -transpile " TA_FILE " > " TA_C " 2>/dev/null");
  code = FileRead(TA_C);
  tests++;
  if (res == 0 && code) correct++;
  if (!code) code = StrNew("");

  /* Element counts, every dimension */
  tests++;
  if (strstr(code, "Leaf leaves[2];") && strstr(code, "long nums[3];") &&
      strstr(code, "int grid[2][3];") && strstr(code, "unsigned char name[8];") &&
      strstr(code, "long loc[4];") && strstr(code, "short cube[2][3][4];")) {
    correct++;
  }

  /* Indexing and field access */
  tests++;
  if (strstr(code, "b.leaves[1].a = 5;") && strstr(code, "b.grid[1][2] = 8;") &&
      strstr(code, "cube[1][2][3] = 9;") && strstr(code, "p->leaves[1].b = 11;") &&
      strstr(code, "b.next->leaves[0].a = 16;")) {
    correct++;
  }

  /* Dereferences */
  tests++;
  if (strstr(code, "*q = 12;") && strstr(code, "q[1] = 13;") &&
      strstr(code, "(*p).nums[0] = 14;") && strstr(code, "(*pp)->nums[1] = 15;") &&
      !strstr(code, "**q") && !strstr(code, "*(")) {
    correct++;
  }

  /* The C compiles and prints what the JIT does (only with a C
   * compiler on the PATH) */
  tests++;
  res = System("command -v cc >/dev/null 2>&1");
  if (res != 0) {
    correct++;
  } else {
    res = System("cc -w -o " TA_EXE " " TA_C " -lm > " TA_OUT " 2>&1 && " TA_TIMEOUT "./" TA_EXE " > " TA_OUT " 2>&1");
    out = FileRead(TA_OUT);
    if (res == 0 && out && !StrCmp(out, ta_expected)) {
      correct++;
    } else if (out) {
      "%s\n", out;
    }
    if (out) Free(out);
  }

  Free(code);
  CleanupTranspileArrTest;
  PrintResult(correct, tests);
  "====\n";
}
