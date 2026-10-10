#include "testhelper.ktm"

/* Print statements ("%Q\n", s;) went straight to C's printf, so the ktm
 * conversions %Q %D %T %C %F printed garbage or the letter, and the
 * compile-time argument check counted the format the C way. A literal
 * format that uses one of them now calls the library's Print, which
 * formats like StrPrint; other print statements still call printf. */

#define PF_FILE "__378_print.ktm"
#define PF_OUT  "__378_print.out"
#define PF_BIN  "__378_print.bin"
#define PF_DATA "__378_print.txt"

U0 CleanupPrintFmt(U0)
{
  Rm(PF_FILE);
  Rm(PF_OUT);
  Rm(PF_BIN);
  Rm(PF_DATA);
}

/* ktm's output (and the program's, piped) for `src`, built with `how`
 * ("-jit", or "-o" to compile a binary and run it); NULL if it can't be
 * written or read. *_res gets the exit status. */
U8 *RunPrint(U8 *src, U8 *how, I64 *_res)
{
  U8 *cmd;
  CleanupPrintFmt;
  if (!FileWrite(PF_FILE, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC)) {
    return NULL;
  }
  if (!StrCmp(how, "-jit")) {
    cmd = "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " PF_FILE " > " PF_OUT " 2>&1";
  } else {
    cmd = "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " PF_FILE " -o " PF_BIN " > " PF_OUT " 2>&1 && ./" PF_BIN " > " PF_OUT " 2>&1";
  }
  *_res = System(cmd);
  return FileRead(PF_OUT);
}

/* `src` runs (both ways) and prints exactly `want` */
Bool Prints(U8 *src, U8 *want)
{
  U8 *out, *how[2] = {"-jit", "-o"};
  I64 res, i;
  Bool ok = TRUE;
  for (i = 0; i < 2; i++) {
    out = RunPrint(src, how[i], &res);
    if (res != 0 || !out || StrCmp(out, want)) {
      ok = FALSE;
      "\n  %s printed:\n%s\n  wanted:\n%s", how[i], out, want;
    }
    Free(out);
  }
  CleanupPrintFmt;
  return ok;
}

/* `src` fails to compile with `msg` */
Bool Rejects(U8 *src, U8 *msg)
{
  I64 res;
  U8 *out = RunPrint(src, "-jit", &res);
  Bool ok = res != 0 && out && strstr(out, msg) != NULL;
  if (!ok && out) "\n%s\n", out;
  Free(out);
  CleanupPrintFmt;
  return ok;
}

I32 Main()
{
  "Test - print statements with ktm formats: ";
  I64 correct = 0, tests = 0;

  /* %Q and %C, with widths, mixed with C conversions and plain print
   * statements: the output stays in order through a pipe */
  tests++;
  if (Prints(
"U0 Main()
{
  U8 *s = \"a\\\"b\\n\";
  \"plain %d %s\\n\", 42, \"x\";
  \"%Q|%C|%5s|%d\\n\", s, 'abc', \"hi\", -7;
  \"plain %05.1f\\n\", 3.14159;
  \"%c%C|%-4C|%3C|\\n\", 'x', 'y', 'ab', 'z';
}
",
"plain 42 x
a\\\"b\\n|ABC|   hi|-7
plain 003.1
xY|AB  |  Z|
")) correct++;

  /* %D %T print what StrPrint prints (the time changes from run to run,
   * so the two lines are compared with each other), %F a file's contents */
  tests++;
  {
    I64 res;
    U8 *out = RunPrint(
"U0 Main()
{
  CDate d = Now;
  U8 *a = MStrPrint(\"%D %T\", d, d);
  FileWrite(\"" PF_DATA "\", \"FILE\", 4);
  \"%D %T\\n\", d, d;
  \"%s\\n\", a;
  \"[%F]\\n\", \"" PF_DATA "\";
}
", "-o", &res);
    U8 *nl = NULL;
    if (out) nl = StrFirstOcc(out, "\n");
    if (res == 0 && nl && StrLen(out) > 20 && !StrNCmp(out, nl + 1, 19) &&
        !StrCmp(nl + 21, "[FILE]\n")) {
      correct++;
    } else {
      "\n  %%D %%T %%F printed:\n%s\n", out;
    }
    Free(out);
    CleanupPrintFmt;
  }

  /* %%Q is a literal, not a conversion */
  tests++;
  if (Prints("U0 Main() { \"100%%Q %d\\n\", 1; }\n", "100%Q 1\n")) correct++;

  /* Print itself, with a format that isn't a literal */
  tests++;
  if (Prints("U0 Main() { U8 *f = \"%Q|%d\\n\"; Print(f, \"t\\tab\", 5); }\n",
             "t\\tab|5\n")) correct++;

  /* the argument check counts ktm conversions */
  tests++;
  if (Rejects("U0 Main() { \"%Q\\n\"; }\n",
              "expects 1 argument but 0 were supplied")) correct++;
  tests++;
  if (Rejects("U0 Main() { \"%Q %d\\n\", \"a\"; }\n",
              "expects 2 arguments but 1 was supplied")) correct++;
  tests++;
  if (Rejects("U0 Main() { \"%D %d\\n\", Now, 1, 2; }\n",
              "expects 2 arguments but 3 were supplied")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
