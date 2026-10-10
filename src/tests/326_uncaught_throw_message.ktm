#include "testhelper.ktm"

/* An uncaught throw printed `uncaught throw: 6574614C` on stdout. It is
 * now reported on stderr, a printable character constant as text, with
 * where the throw is: `uncaught error <code> (<file>:<line>)`. */

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

  if (Uncaught("throw('Late');", "uncaught error 'Late' (" UT_SRC ":3)\n", "")) correct++;
  if (Uncaught("\"before\\n\"; throw('A');",
               "uncaught error 'A' (" UT_SRC ":3)\n", "before\n")) correct++;
  if (Uncaught("throw('LongName');", "uncaught error 'LongName' (" UT_SRC ":3)\n", ""))
    correct++;
  if (Uncaught("throw(0x0A41);", "uncaught error 2625 (" UT_SRC ":3)\n", "")) correct++;
  if (Uncaught("throw(0);", "uncaught error 0 (" UT_SRC ":3)\n", "")) correct++;
  if (Uncaught("throw(-12);", "uncaught error -12 (" UT_SRC ":3)\n", "")) correct++;
  if (Uncaught("throw(0x8000000000000000);",
               "uncaught error -9223372036854775808 (" UT_SRC ":3)\n", "")) correct++;
  /* After a try has been used and left, the chain is empty again. */
  if (Uncaught("try { throw(1); } catch { \"c\\n\"; } throw('Bye');",
               "uncaught error 'Bye' (" UT_SRC ":3)\n", "c\n")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
