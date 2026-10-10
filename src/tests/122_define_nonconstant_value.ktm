#include "testhelper.ktm"

#define NONCONST_FILE "__122_define_nonconstant_value.ktm"
#define NONCONST_OUT  "__122_define_nonconstant_value.out"

/* Defines that still have to work */
#define NC_INT   2
#define NC_SUM   (NC_INT + 3)
#define NC_FLT   (1.5 * 2)
#define NC_STR   "he" "llo"
#define NC_PSTR  ("abc")
#define NC_CAST  (300(U8) + 1)

#define NC_MSG "the value must be a constant expression or a string, made of literals and other macros"

U0 CleanupNonConstTest(U0)
{
  Rm(NONCONST_FILE);
  Rm(NONCONST_OUT);
  Rm("./a.out");
}

/* Compile `source`, which must fail (exit status 1) with `msg` reported
 * at `loc` (" --> file:line:col") and, when `use_loc` is not NULL, with
 * `X` reported as undefined at `use_loc`: lexing carried on past the
 * define and left X undefined. The old path exited from inside the
 * evaluator with "ERROR: Expected integer expression" and no location. */
Bool CompileFailsAt(U8 *source, U8 *msg, U8 *loc, U8 *use_loc)
{
  U8 *out, *want, *want_use = NULL;
  I64 res;
  Bool ok = TRUE;

  CleanupNonConstTest;

  if (!FileWrite(NONCONST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " NONCONST_FILE " > " NONCONST_OUT " 2>&1; exit $(( $? == 1 ? 7 : 0 ))");
  if (res == 0) ok = FALSE;

  out = FileRead(NONCONST_OUT);
  if (!out) {
    CleanupNonConstTest;
    return FALSE;
  }

  want = MStrPrint("error: %s\n --> " NONCONST_FILE ":%s\n", msg, loc);
  if (use_loc) {
    want_use = MStrPrint("error: Variable or function `X` has not been defined\n --> "
                         NONCONST_FILE ":%s\n", use_loc);
  }
  if (!strstr(out, want)) ok = FALSE;
  if (want_use && !strstr(out, want_use)) ok = FALSE;
  if (strstr(out, "Expected integer expression")) ok = FALSE;
  if (strstr(out, "Expected float expression")) ok = FALSE;
  if (!ok) "%s\n", out;

  Free(want);
  if (want_use) Free(want_use);
  Free(out);
  CleanupNonConstTest;
  return ok;
}

/* The report: an int macro plus a string */
Bool TestIntMacroPlusString(U0)
{
  return CompileFailsAt(
"#define Y 2
#define X (Y + \"s\")
U0 Main() { \"%d\\n\", X; }
",
    "#define X: " NC_MSG, "2:11", "3:21");
}

Bool TestIntPlusNegString(U0)
{
  return CompileFailsAt(
"#define X (1 + -\"s\")
U0 Main() { \"%d\\n\", X; }
",
    "#define X: " NC_MSG, "1:11", "2:21");
}

Bool TestIntTimesCastString(U0)
{
  return CompileFailsAt(
"#define X (3 * \"s\"(I64))
U0 Main() { \"%d\\n\", X; }
",
    "#define X: " NC_MSG, "1:11", "2:21");
}

Bool TestFloatPlusString(U0)
{
  return CompileFailsAt(
"#define X (2.5 + \"s\")
U0 Main() { \"%d\\n\", X; }
",
    "#define X: " NC_MSG, "1:11", "2:21");
}

Bool TestFloatMacroPlusStringMacro(U0)
{
  return CompileFailsAt(
"#define F 1.5
#define S \"s\"
#define X (F * S)
U0 Main() { \"%d\\n\", X; }
",
    "#define X: " NC_MSG, "3:11", "4:21");
}

Bool TestStringMinusInt(U0)
{
  return CompileFailsAt(
"#define X (\"a\" - 1)
U0 Main() { \"%d\\n\", X; }
",
    "#define X expected string but got: AST_BINOP", "1:11", "2:21");
}

/* `-` on a string is a type error now (test 134), as in `1 + -"s"` */
Bool TestNegString(U0)
{
  return CompileFailsAt(
"#define X (-\"s\")
U0 Main() { \"%d\\n\", X; }
",
    "#define X: " NC_MSG, "1:11", "2:21");
}

Bool TestCastString(U0)
{
  return CompileFailsAt(
"#define X (\"s\"(I64))
U0 Main() { \"%d\\n\", X; }
",
    "#define X expected string but got: AST_CAST", "1:11", "2:21");
}

/* Both bad defines reported, lexing carried on after each */
Bool TestTwoBadDefines(U0)
{
  return CompileFailsAt(
"#define A (1 + \"s\")
#define B (2 * -\"t\")
#define X (A + B)
U0 Main() { \"%d\\n\", X; }
",
    "#define B: " NC_MSG, "2:11", NULL);
}

I32 Main()
{
  "Test - #define with a non-constant value: \n";
  I64 tests = 12;
  I64 correct = 0;

  if (TestIntMacroPlusString()) correct++;
  else "Failed - TestIntMacroPlusString()\n";

  if (TestIntPlusNegString()) correct++;
  else "Failed - TestIntPlusNegString()\n";

  if (TestIntTimesCastString()) correct++;
  else "Failed - TestIntTimesCastString()\n";

  if (TestFloatPlusString()) correct++;
  else "Failed - TestFloatPlusString()\n";

  if (TestFloatMacroPlusStringMacro()) correct++;
  else "Failed - TestFloatMacroPlusStringMacro()\n";

  if (TestStringMinusInt()) correct++;
  else "Failed - TestStringMinusInt()\n";

  if (TestNegString()) correct++;
  else "Failed - TestNegString()\n";

  if (TestCastString()) correct++;
  else "Failed - TestCastString()\n";

  if (TestTwoBadDefines()) correct++;
  else "Failed - TestTwoBadDefines()\n";

  if (NC_SUM == 5 && NC_CAST == 45) correct++;
  else "Failed - int defines: %d %d\n", NC_SUM, NC_CAST;

  if (NC_FLT == 3.0) correct++;
  else "Failed - float define: %f\n", NC_FLT;

  if (!StrCmp(NC_STR, "hello") && !StrCmp(NC_PSTR, "abc")) correct++;
  else "Failed - string defines: %s %s\n", NC_STR, NC_PSTR;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
