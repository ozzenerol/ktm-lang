#include "testhelper.ktm"

#define FN_MACRO_TEST_FILE "__72_fn_macro_fail.ktm"
#define FN_MACRO_TEST_OUT  "__72_fn_macro_fail.out"

/* Object-like macros must keep working: `#define NAME (expr)` with a
 * space before the paren is an ordinary define, not a function-like
 * macro. */
#define CALC (2 * 21)
#define CALC_BASE 40
#define CALC_REF  (CALC_BASE + 2)
#define CALC_STR  "forty two"
#define CALC_F64  (40.5 + 1.5)
#define CALC_CHAR 'A'
#define CALC_CHR  'a' + 1
#define CALC_SUFFIX 2048U
#define CALC_FLAG
#define CALC_GONE 1
#undef CALC_GONE

U0 CleanupFnMacroTest(U0)
{
  Rm(FN_MACRO_TEST_FILE);
  Rm(FN_MACRO_TEST_OUT);
  Rm("./a.out");
}

/* The compile must fail normally (exit status 1), not die on a signal,
 * and print `expected`. */
Bool CompileFailsWith(U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;

  CleanupFnMacroTest;

  if (!FileWrite(FN_MACRO_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " FN_MACRO_TEST_FILE " > " FN_MACRO_TEST_OUT " 2>&1");
  if (res != 1) {
    "ktm exit status %d, expected a normal compile failure\n", res;
    CleanupFnMacroTest;
    return FALSE;
  }

  out = FileRead(FN_MACRO_TEST_OUT);
  if (!out) {
    CleanupFnMacroTest;
    return FALSE;
  }

  if (!strstr(out, expected)) {
    "%s\n", out;
    Free(out);
    CleanupFnMacroTest;
    return FALSE;
  }

  Free(out);
  CleanupFnMacroTest;
  return TRUE;
}

Bool TestFunctionLikeMacro(U0)
{
  return CompileFailsWith(
"#define DOUBLE(x) ((x)*2)
U0 Main() { \"%d\\n\", DOUBLE(21); }
",
    "function-like macros are not supported: #define DOUBLE(x) ((x)*2)");
}

Bool TestEmptyParamMacro(U0)
{
  return CompileFailsWith(
"#define X()
U0 Main() { }
",
    "function-like macros are not supported: #define X()");
}

Bool TestUnclosedParamMacro(U0)
{
  return CompileFailsWith(
"#define X(
U0 Main() { }
",
    "function-like macros are not supported: #define X(");
}

Bool TestDefineNoName(U0)
{
  return CompileFailsWith(
"#define
U0 Main() { }
",
    "Syntax is: #define <TK_IDENT> <value>");
}

Bool TestDefineBadName(U0)
{
  /* `1 x`, not `1x`: that is now a malformed number literal. */
  return CompileFailsWith(
"#define 1 x
U0 Main() { }
",
    "Syntax is: #define <TK_IDENT> <value>");
}

/* An unknown name inside a multi-token value used to crash the
 * macro evaluator. */
Bool TestDefineUnknownName(U0)
{
  return CompileFailsWith(
"#define Y (a + 1)
U0 Main() { }
",
    "#define Y: the value must be a constant expression");
}

Bool TestDefineUnclosedValue(U0)
{
  return CompileFailsWith(
"#define Y (1 + 2
U0 Main() { }
",
    "#define Y: the value must be a constant expression");
}

Bool TestObjectLikeMacros(U0)
{
  Bool ok = TRUE;
  if (CALC != 42) ok = FALSE;
  if (CALC_REF != 42) ok = FALSE;
  if (StrCmp(CALC_STR, "forty two")) ok = FALSE;
  if (CALC_F64 != 42.0) ok = FALSE;
  if (CALC_CHAR != 'A') ok = FALSE;
  if (CALC_CHR != 'b') ok = FALSE;
  if (CALC_SUFFIX != 2048) ok = FALSE;
#ifndef CALC_FLAG
  ok = FALSE;
#endif
#ifdef CALC_GONE
  ok = FALSE;
#endif
  return ok;
}

I32 Main()
{
  "Test - Function-like macro errors: \n";
  I64 tests = 8;
  I64 correct = 0;

  if (TestFunctionLikeMacro()) correct++;
  else "Failed - TestFunctionLikeMacro()\n";

  if (TestEmptyParamMacro()) correct++;
  else "Failed - TestEmptyParamMacro()\n";

  if (TestUnclosedParamMacro()) correct++;
  else "Failed - TestUnclosedParamMacro()\n";

  if (TestDefineNoName()) correct++;
  else "Failed - TestDefineNoName()\n";

  if (TestDefineBadName()) correct++;
  else "Failed - TestDefineBadName()\n";

  if (TestDefineUnknownName()) correct++;
  else "Failed - TestDefineUnknownName()\n";

  if (TestDefineUnclosedValue()) correct++;
  else "Failed - TestDefineUnclosedValue()\n";

  if (TestObjectLikeMacros()) correct++;
  else "Failed - TestObjectLikeMacros()\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
