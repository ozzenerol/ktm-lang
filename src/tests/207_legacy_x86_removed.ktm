#include "testhelper.ktm"

/* The legacy AST-based x86 backend was removed: --use-legacy-x86 must
 * fail with a clear message, not compile with a broken code generator. */

#define RM_SRC "__207_legacy.ktm"
#define RM_BIN "./__207_legacy"
#define RM_OUT "__207_legacy.out"

U0 Cleanup()
{
  Rm(RM_SRC);
  Rm(RM_BIN);
  Rm(RM_OUT);
}

U8 *g_program = "U0 Main() { \"hi\\n\"; }\n";

I32 Main()
{
  I64 correct = 0, tests = 3;
  "Test - --use-legacy-x86 is rejected: ";
  U8 *out = NULL;
  Cleanup;
  if (FileWrite(RM_SRC, g_program, StrLen(g_program), O_CREAT | O_RDWR | O_TRUNC)) {
    if (System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} --use-legacy-x86 "
               RM_SRC " -o " RM_BIN " > " RM_OUT " 2>&1") != 0) correct++;
    out = FileRead(RM_OUT);
    if (out && strstr(out, "--use-legacy-x86 was removed")) correct++;
    else if (out) "\n%s", out;
    /* the same program still builds with the default backend */
    if (System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               RM_SRC " -o " RM_BIN " > " RM_OUT " 2>&1") == 0) correct++;
  }
  if (out) Free(out);
  Cleanup;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
