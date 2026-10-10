#include "testhelper.ktm"

/* New vector functions: IntVecSet/FloatVecSet/PtrVecSet (bounds checked
 * like Get: an index outside [0, size) prints an error and exits 1) and
 * IntVecLast/FloatVecLast/PtrVecLast (peek; sentinel -1 / -1.0 / NULL and
 * *_ok = FALSE when empty). */

#define VS_FILE "__238_vecset.ktm"
#define VS_OUT  "__238_vecset.out"
#define VS_BIN  "__238_vecset.bin"

U0 CleanupFiles()
{
  Rm(VS_FILE);
  Rm(VS_OUT);
  Rm(VS_BIN);
}

Bool RunExpectingExit(U8 *src, U8 *expected)
{
  CleanupFiles;
  if (!FileWrite(VS_FILE, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  I64 res = System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                   "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                   VS_FILE " -o " VS_BIN " > " VS_OUT " 2>&1 && "
                   "./" VS_BIN " 2> " VS_OUT " >/dev/null; exit $?");
  U8 *out = FileRead(VS_OUT);
  Bool ok = out && res == 1 && strstr(out, expected) != NULL &&
            strstr(out, "unreachable") == NULL;
  if (!ok) "status %d, output: %s\n", res, out;
  Free(out);
  CleanupFiles;
  return ok;
}

I64 g_seed = 3;
I64 NextRand()
{
  g_seed = (g_seed * 6364136223846793005 + 1442695040888963407);
  return (g_seed >> 33) & 0x7FFFFFFF;
}

/* Random push/pop/set/last against an array model, all three types */
Bool ModelRun(I64 ops)
{
  I64 model[2048];
  I64 n = 0;
  IntVec *iv = IntVecNew(1);
  FloatVec *fv = FloatVecNew(1);
  PtrVec *pv = PtrVecNew(1);
  Bool ok = TRUE, has;
  for (I64 op = 0; op < ops && ok; ++op) {
    I64 r = NextRand() % 10, x = NextRand() % 100000;
    if (r < 4 && n < 2048) {
      IntVecPush(iv, x);
      FloatVecPush(fv, x(F64) / 4.0);
      PtrVecPush(pv, x(U0 *));
      model[n++] = x;
    } else if (r < 6 && n > 0) {
      n--;
      IntVecPop(iv, &has);
      FloatVecPop(fv, &has);
      PtrVecPop(pv, &has);
    } else if (r < 9 && n > 0) {
      I64 idx = NextRand() % n;
      IntVecSet(iv, idx, x);
      FloatVecSet(fv, idx, x(F64) / 4.0);
      PtrVecSet(pv, idx, x(U0 *));
      model[idx] = x;
    }
    if (n > 0) {
      I64 last = model[n - 1];
      ok = IntVecLast(iv, &has) == last && has &&
           FloatVecLast(fv) == last(F64) / 4.0 && PtrVecLast(pv)(I64) == last;
      I64 idx = NextRand() % n;
      ok = ok && IntVecGet(iv, idx) == model[idx] &&
           FloatVecGet(fv, idx) == model[idx](F64) / 4.0 &&
           PtrVecGet(pv, idx)(I64) == model[idx];
    } else {
      ok = IntVecLast(iv, &has) == -1 && !has;
      ok = ok && FloatVecLast(fv, &has) == -1.0 && !has;
      ok = ok && PtrVecLast(pv, &has) == NULL && !has;
    }
    ok = ok && iv->size == n && fv->size == n && pv->size == n;
  }
  IntVecRelease(iv);
  FloatVecRelease(fv);
  PtrVecRelease(pv);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - vector set and last: ";

  tests++; if (ModelRun(20000)) correct++;
  else "model mismatch\n";

  tests++;
  if (RunExpectingExit(
"I32 Main() { IntVec *v = IntVecNew(); IntVecPush(v, 1);\n"
"  IntVecSet(v, 1, 5); \"unreachable\\n\"; return 0; }\n",
      "IntVecSet: index 1 out of range (size 1)")) correct++;
  tests++;
  if (RunExpectingExit(
"I32 Main() { FloatVec *v = FloatVecNew();\n"
"  FloatVecSet(v, 0, 1.0); \"unreachable\\n\"; return 0; }\n",
      "FloatVecSet: index 0 out of range (size 0)")) correct++;
  tests++;
  if (RunExpectingExit(
"I32 Main() { PtrVec *v = PtrVecNew(); PtrVecPush(v, NULL);\n"
"  PtrVecSet(v, -3, NULL); \"unreachable\\n\"; return 0; }\n",
      "PtrVecSet: index -3 out of range (size 1)")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
