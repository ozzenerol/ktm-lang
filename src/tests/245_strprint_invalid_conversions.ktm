#include "testhelper.ktm"

/* StrPrint with a conversion it doesn't know: a trailing '%' read past
 * the format's NUL; an unsupported conversion didn't take its argument,
 * so the later conversions got the wrong ones (`%ld %s` crashed; the
 * cases use letters that stay unsupported once %ld is), the
 * diagnostic named the next character, went to stdout, and the bad
 * character was dropped. Now the conversion is kept as written (as C
 * prints it), its argument is taken, and the diagnostic names it on
 * stderr. */

#define FB_SRC "./ktm-245-fb.ktm"
#define FB_OUT "./ktm-245-fb.out"
#define FB_ERR "./ktm-245-fb.err"

Bool Is(U8 *got, U8 *want)
{
  Bool ok = got && !StrCmp(got, want);
  if (!ok) "got [%s] want [%s]\n", got, want;
  Free(got);
  return ok;
}

I32 Main()
{
  "Test - StrPrint invalid conversions: ";
  I64 correct = 0, tests = 0;

  /* A '%' at the end stops at the NUL */
  U8 fmt[32];
  MemSet(fmt, 0, 32);
  StrCpy(fmt, "100%");
  StrCpy(fmt + 5, "LEAKED");
  tests++;
  if (Is(StrPrint(NULL, fmt), "100%")) correct++;
  tests++;
  if (Is(StrPrint(NULL, "x%-5"), "x%-5")) correct++;

  /* Unknown conversions are kept and take their argument */
  tests++;
  if (Is(StrPrint(NULL, "a%yb"), "a%yb")) correct++;
  tests++;
  U8 *wf = "%w %s"; /* a variable: the compile-time check counts C's conversions */
  if (Is(StrPrint(NULL, wf, 5, "five"), "%w five")) correct++;
  tests++;
  /* (a variable: the compile-time format check counts C's conversions) */
  U8 *f = "%y|%d|%s";
  if (Is(StrPrint(NULL, f, 1, 2, "three"), "%y|2|three")) correct++;
  /* A character that isn't a letter ends no conversion: no argument */
  tests++;
  if (Is(StrPrint(NULL, "%! %d", 3), "%! 3")) correct++;
  /* Known conversions are unchanged */
  tests++;
  if (Is(StrPrint(NULL, "%d%%%s|%5d|%-3x|", 7, "s", 42, 255), "7%s|   42|ff |")) correct++;

  /* The diagnostic goes to stderr and names the right character */
  tests++;
  U8 *prog = "U0 Main() { U8 *s = StrPrint(NULL, \"%k-%d-%\", 1, 2); "
             "\"[%s]\\n\", s; Free(s); }\n";
  System("rm -f " FB_SRC " " FB_OUT " " FB_ERR);
  FileWrite(FB_SRC, prog, StrLen(prog), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-jit " FB_SRC " > " FB_OUT " 2> " FB_ERR);
  U8 *out = FileRead(FB_OUT);
  U8 *err = FileRead(FB_ERR);
  if (out && err && !StrCmp(out, "[%k-2-%]\n") &&
      strstr(err, "Invalid format char: 'k'") &&
      strstr(err, "'%' with no conversion at the end")) correct++;
  else "out [%s] err [%s]\n", out, err;
  Free(out);
  Free(err);
  System("rm -f " FB_SRC " " FB_OUT " " FB_ERR);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
