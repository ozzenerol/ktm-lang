#include "testhelper.ktm"

/* An error on a macro use pointed at the #define: `I64 x = 2 N;` with
 * `#define N 3` reported "Unexpected integer `3`" at the define's line
 * and column and showed `#define N 3`. The parser is handed the macro's
 * value, which carried the position of the #define; it is now placed
 * where the macro is written, the underline covers the name and a note
 * says which macro it came from. */

#define MACRO_TEST_FILE "__144_macro_use_error_location.ktm"
#define MACRO_TEST_HDR  "__144_macro_use_error_location.ktmh"
#define MACRO_TEST_OUT  "__144_macro_use_error_location.out"

U0 CleanupMacroTest(U0)
{
  Rm(MACRO_TEST_FILE);
  Rm(MACRO_TEST_HDR);
  Rm(MACRO_TEST_OUT);
  Rm("./a.out");
}

/* Run ktm on `source` (with `header` as MACRO_TEST_HDR if given): does it
 * fail (or, with `ok`, pass) and print `expected`, and not `unwanted`? */
Bool CompileOutputHas(U8 *source, U8 *header, Bool ok, U8 *expected,
                      U8 *unwanted=NULL)
{
  U8 *out;
  I64 res;
  Bool good = TRUE;

  CleanupMacroTest;

  if (header && !FileWrite(MACRO_TEST_HDR, header, StrLen(header),
                           O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  if (!FileWrite(MACRO_TEST_FILE, source, StrLen(source),
                 O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " MACRO_TEST_FILE " > " MACRO_TEST_OUT " 2>&1");
  if ((res == 0) != ok) good = FALSE;

  out = FileRead(MACRO_TEST_OUT);
  if (!out) {
    CleanupMacroTest;
    return FALSE;
  }
  if (!strstr(out, expected)) good = FALSE;
  if (unwanted && strstr(out, unwanted)) good = FALSE;
  if (!good) {
    "%s\n", out;
  }

  Free(out);
  CleanupMacroTest;
  return good;
}

I32 Main()
{
  I64 correct = 0, tests = 9;

  "Test - error on a macro use is reported where it is used: ";

  /* the issue's repro */
  if (CompileOutputHas(
      "#define N 3\nU0 Main() {\n  I64 x = 2 N;\n}\n", NULL, FALSE,
      MACRO_TEST_FILE ":3:13\n"
      "     |\n"
      "   3 |      I64 x = 2 N;\n"
      "     |                ^ expanded from macro `N`",
      "#define N 3\n     |")) correct++;

  /* a string macro: the underline is the name, not the string */
  if (CompileOutputHas(
      "#define STR \"hello\"\nU0 Main() {\n  I64 x = 2 STR;\n}\n", NULL, FALSE,
      MACRO_TEST_FILE ":3:13\n"
      "     |\n"
      "   3 |      I64 x = 2 STR;\n"
      "     |                ^^^ expanded from macro `STR`")) correct++;

  /* a float macro */
  if (CompileOutputHas(
      "#define PI 3.25\nU0 Main() {\n  F64 x = 2 PI;\n}\n", NULL, FALSE,
      MACRO_TEST_FILE ":3:13\n"
      "     |\n"
      "   3 |      F64 x = 2 PI;\n"
      "     |                ^^ expanded from macro `PI`")) correct++;

  /* a macro defined as another macro */
  if (CompileOutputHas(
      "#define N 3\n#define LONGNAME N\nU0 Main() {\n  I64 x = 2 LONGNAME;\n}\n",
      NULL, FALSE,
      MACRO_TEST_FILE ":4:13\n"
      "     |\n"
      "   4 |      I64 x = 2 LONGNAME;\n"
      "     |                ^^^^^^^^ expanded from macro `LONGNAME`")) correct++;

  /* a folded expression macro */
  if (CompileOutputHas(
      "#define N 3\n#define T (N + 1)\nU0 Main() {\n  I64 x = 2 T;\n}\n",
      NULL, FALSE,
      MACRO_TEST_FILE ":4:13\n"
      "     |\n"
      "   4 |      I64 x = 2 T;\n"
      "     |                ^ expanded from macro `T`")) correct++;

  /* the "missing `;`" note on a line ending in a macro */
  if (CompileOutputHas(
      "#define N 3\nU0 Main() {\n  I64 x = N\n  I64 y = 2;\n}\n", NULL, FALSE,
      "info: perhaps a missing `;` at the end of this line?\n"
      " --> " MACRO_TEST_FILE ":3:11\n"
      "     |\n"
      "   3 |      I64 x = N\n"
      "     |              ^ expanded from macro `N`")) correct++;

  /* a macro from an included file, used on a line of the includer */
  if (CompileOutputHas(
      "#include \"" MACRO_TEST_HDR "\"\nU0 Main() {\n  I64 x = 2 HN;\n}\n",
      "\n\n\n\n#define HN 7\n", FALSE,
      MACRO_TEST_FILE ":3:13\n"
      "     |\n"
      "   3 |      I64 x = 2 HN;\n"
      "     |                ^^ expanded from macro `HN`")) correct++;

  /* macros still expand to their values: integer, float, string,
   * nested, folded, and in #if */
  if (CompileOutputHas(
      "#define N 3\n#define PI 1.5\n#define S \"s\"\n#define M N\n"
      "#define T (N * 2)\n#if N > 2\n#define B 1\n#else\n#define B 0\n#endif\n"
      "U0 Main() { \"%d %.1f %s %d %d %d\\n\", N, PI, S, M, T, B; }\nMain;\n",
      NULL, TRUE, "3 1.5 s 3 6 1\n")) correct++;

  /* the same macro used twice on a line: each at its own column */
  if (CompileOutputHas(
      "#define N 3\nU0 Main() {\n  I64 x = N + N N;\n}\n", NULL, FALSE,
      MACRO_TEST_FILE ":3:17\n"
      "     |\n"
      "   3 |      I64 x = N + N N;\n"
      "     |                    ^ expanded from macro `N`")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
