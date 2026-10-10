#include "testhelper.ktm"

/* IntVecGet/FloatVecGet/PtrVecGet only checked the index under
 * `#ifdef DEBUG`, which the library is never built with: IntVecGet(v, -1)
 * read the malloc header. Out-of-range indexes now print
 * "<Fn>: index N out of range (size M)" on stderr and exit with 1. */

#define VB_FILE "__235_vecbounds.ktm"
#define VB_OUT  "__235_vecbounds.out"
#define VB_BIN  "__235_vecbounds.bin"

U0 CleanupFiles()
{
  Rm(VB_FILE);
  Rm(VB_OUT);
  Rm(VB_BIN);
}

/* Compile and run `src`: TRUE if it exits with 1, prints `expected` on
 * stderr and not "unreachable" */
Bool RunExpectingExit(U8 *src, U8 *expected)
{
  CleanupFiles;
  if (!FileWrite(VB_FILE, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  I64 res = System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                   "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                   VB_FILE " -o " VB_BIN " > " VB_OUT " 2>&1 && "
                   "./" VB_BIN " 2> " VB_OUT " >/dev/null; exit $?");
  U8 *out = FileRead(VB_OUT);
  Bool ok = out && res == 1 && strstr(out, expected) != NULL &&
            strstr(out, "unreachable") == NULL;
  if (!ok) "status %d, output: %s\n", res, out;
  Free(out);
  CleanupFiles;
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - vector bounds checks: ";

  /* In range: unchanged */
  IntVec *iv = IntVecNew();
  FloatVec *fv = FloatVecNew();
  PtrVec *pv = PtrVecNew();
  for (I64 i = 0; i < 100; ++i) {
    IntVecPush(iv, i * 2);
    FloatVecPush(fv, i * 0.5);
    PtrVecPush(pv, (i + 1)(U0 *));
  }
  Bool ok = TRUE;
  for (I64 i = 0; i < 100; ++i) {
    if (IntVecGet(iv, i) != i * 2 || FloatVecGet(fv, i) != i * 0.5 ||
        PtrVecGet(pv, i)(I64) != i + 1) ok = FALSE;
  }
  tests++; if (ok) correct++;
  IntVecRelease(iv);
  FloatVecRelease(fv);
  PtrVecRelease(pv);

  tests++;
  if (RunExpectingExit(
"I32 Main() { IntVec *v = IntVecNew(); IntVecPush(v, 1);\n"
"  IntVecGet(v, -1); \"unreachable\\n\"; return 0; }\n",
      "IntVecGet: index -1 out of range (size 1)")) correct++;

  tests++;
  if (RunExpectingExit(
"I32 Main() { IntVec *v = IntVecNew(); IntVecPush(v, 1); IntVecPush(v, 2);\n"
"  IntVecGet(v, 2); \"unreachable\\n\"; return 0; }\n",
      "IntVecGet: index 2 out of range (size 2)")) correct++;

  tests++;
  if (RunExpectingExit(
"I32 Main() { FloatVec *v = FloatVecNew();\n"
"  FloatVecGet(v, 0); \"unreachable\\n\"; return 0; }\n",
      "FloatVecGet: index 0 out of range (size 0)")) correct++;

  tests++;
  if (RunExpectingExit(
"I32 Main() { PtrVec *v = PtrVecNew(); PtrVecPush(v, NULL);\n"
"  PtrVecGet(v, 40); \"unreachable\\n\"; return 0; }\n",
      "PtrVecGet: index 40 out of range (size 1)")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
