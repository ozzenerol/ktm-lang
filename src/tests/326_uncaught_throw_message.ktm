#include "testhelper.ktm"

/* An uncaught throw printed `uncaught throw: 6574614C` on stdout. It is
 * now reported on stderr, a printable character constant as text. */

#define UT_SRC "__326_uncaught.ktm"
#define UT_BIN "./__326_uncaught.out"
#define UT_OUT "__326_uncaught.stdout"
#define UT_ERR "__326_uncaught.stderr"

Bool Uncaught(U8 *body, U8 *want_err, U8 *want_out)
{
  U8 *src = StrPrint(NULL, "U0 Main()\n{\n%s\n}\n", body);
  Bool ok = FALSE;
  if (FileWrite(UT_SRC, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC) &&
      System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
             UT_SRC " -o " UT_BIN) == 0) {
    I64 res = System(UT_BIN " > " UT_OUT " 2> " UT_ERR);
    U8 *out = FileRead(UT_OUT), *err = FileRead(UT_ERR);
    if (out && err) {
      ok = res == 1 && !StrCmp(err, want_err) && !StrCmp(out, want_out);
      if (!ok) "\nexit %d, stdout [%s], stderr [%s]\n", res, out, err;
    }
    Free(out);
    Free(err);
  }
  Free(src);
  Rm(UT_SRC);
  Rm(UT_BIN);
  Rm(UT_OUT);
  Rm(UT_ERR);
  return ok;
}

I32 Main()
{
  "Test - uncaught throw message: ";
  I64 correct = 0, tests = 8;

  if (Uncaught("throw('Late');", "uncaught throw: 'Late'\n", "")) correct++;
  if (Uncaught("\"before\\n\"; throw('A');",
               "uncaught throw: 'A'\n", "before\n")) correct++;
  if (Uncaught("throw('LongName');", "uncaught throw: 'LongName'\n", ""))
    correct++;
  if (Uncaught("throw(0x0A41);", "uncaught throw: 2625\n", "")) correct++;
  if (Uncaught("throw(0);", "uncaught throw: 0\n", "")) correct++;
  if (Uncaught("throw(-12);", "uncaught throw: -12\n", "")) correct++;
  if (Uncaught("throw(0x8000000000000000);",
               "uncaught throw: -9223372036854775808\n", "")) correct++;
  /* After a try has been used and left, the chain is empty again. */
  if (Uncaught("try { throw(1); } catch { \"c\\n\"; } throw('Bye');",
               "uncaught throw: 'Bye'\n", "c\n")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
