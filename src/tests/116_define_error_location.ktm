#include "testhelper.ktm"

#define DEF_LOC_FILE "__116_define_error_location.ktm"
#define DEF_LOC_OUT  "__116_define_error_location.out"

U0 CleanupDefLocTest(U0)
{
  Rm(DEF_LOC_FILE);
  Rm(DEF_LOC_OUT);
  Rm("./a.out");
}

/* Compile `source`, which must fail with `msg` reported at `loc`
 * (" --> file:line:col"), the source excerpt showing `excerpt` and,
 * when not NULL, the underline `caret` (the `|` and everything after
 * it on the underline's line). */
Bool CompileFailsAt(U8 *source, U8 *msg, U8 *loc, U8 *excerpt, U8 *caret)
{
  U8 *out, *want_loc, *want_excerpt, *want_caret = NULL;
  I64 res;
  Bool ok = TRUE;

  CleanupDefLocTest;

  if (!FileWrite(DEF_LOC_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " DEF_LOC_FILE " > " DEF_LOC_OUT " 2>&1");
  if (res == 0) {
    CleanupDefLocTest;
    return FALSE;
  }

  out = FileRead(DEF_LOC_OUT);
  if (!out) {
    CleanupDefLocTest;
    return FALSE;
  }

  want_loc = MStrPrint("error: %s\n --> " DEF_LOC_FILE ":%s\n", msg, loc);
  want_excerpt = MStrPrint(" |    %s\n", excerpt);
  if (caret) want_caret = MStrPrint("\n     |%s", caret);

  if (!strstr(out, want_loc)) ok = FALSE;
  if (!strstr(out, want_excerpt)) ok = FALSE;
  if (want_caret && !strstr(out, want_caret)) ok = FALSE;
  if (!ok) "%s\n", out;

  Free(want_loc);
  Free(want_excerpt);
  if (want_caret) Free(want_caret);
  Free(out);
  CleanupDefLocTest;
  return ok;
}

Bool TestDefineAlone(U0)
{
  return CompileFailsAt(
"#define X (zz+1)
U0 Main() {}
",
    "#define X: the value must be a constant expression or a string, made of literals and other macros",
    "1:11", "#define X (zz+1)", "              ^^^^^^\n");
}

/* Indented, with trailing blanks, after a class */
Bool TestDefineAfterClass(U0)
{
  return CompileFailsAt(
"class Foo { I64 a; };
  #define X (zz+1)
U0 Main() {}
",
    "#define X: the value must be a constant expression or a string, made of literals and other macros",
    "2:13", "  #define X (zz+1)", "                ^^^^^^\n");
}

Bool TestDefineNotNumeric(U0)
{
  return CompileFailsAt(
"#define X zz
U0 Main() {}
",
    "Error while parsing #define X; #define must be a numerical expression or a string",
    "1:11", "#define X zz", "              ^^\n");
}

Bool TestDefineNothing(U0)
{
  return CompileFailsAt(
"#define
U0 Main() {}
",
    "Syntax is: #define <TK_IDENT> <value> got nothing",
    "1:1", "#define", "    ^^^^^^^\n");
}

Bool TestIfCondition(U0)
{
  return CompileFailsAt(
"I64 g;
#if (zz+
#endif
U0 Main() {}
",
    "#if: the condition must be a constant expression made of literals, macros and defined()",
    "2:5", "#if (zz+", "        ^^^^\n");
}

Bool TestElifCondition(U0)
{
  return CompileFailsAt(
"#if 0
#elif (1+
#endif
U0 Main() {}
",
    "#elif: the condition must be a constant expression made of literals, macros and defined()",
    "2:7", "#elif (1+", "          ^^^\n");
}

Bool TestIfExtraToken(U0)
{
  return CompileFailsAt(
"#if 1 2
#endif
U0 Main() {}
",
    "#if: unexpected '2' after the condition",
    "1:7", "#if 1 2", "          ^\n");
}

Bool TestIfEmpty(U0)
{
  return CompileFailsAt(
"#if
#endif
U0 Main() {}
",
    "a #if must evaluate some expression",
    "1:1", "#if", "    ^^^\n");
}

/* At the #if, not one line past the end of the file */
Bool TestUnterminatedIf(U0)
{
  return CompileFailsAt(
"U0 Main() {}
#if 0
I64 y;
",
    "Unterminated #if", "2:1", "#if 0", "    ^^^\n");
}

/* The excerpt re-lexes the line: an unknown directive used to make
 * that exit before anything was printed */
Bool TestUnknownDirective(U0)
{
  return CompileFailsAt(
"#foo bar
U0 Main() {}
",
    "invalid preprocessor directive '#foo'", "1:1", "#foo bar", "    ^^^^\n");
}

Bool TestIncludeSyntax(U0)
{
  return CompileFailsAt(
"#include 42
U0 Main() {}
",
    "Syntax is: #include \"<value>\" got: TK_I64        42",
    "1:10", "#include 42", NULL);
}

/* No operand: at the directive, not at the next line's first token */
Bool TestUndefNothing(U0)
{
  return CompileFailsAt(
"#undef
U0 Main() {}
",
    "Syntax is: #undef <TK_IDENT>", "1:1", "#undef", "    ^^^^^^\n");
}

Bool TestLinkSyntax(U0)
{
  return CompileFailsAt(
"I64 g;
  #link 42
U0 Main() {}
",
    "Syntax is: #link \"<path>\" or #link <libname> got: TK_I64        42",
    "2:9", "  #link 42", "            ^^\n");
}

I32 Main()
{
  "Test - #define error location: \n";
  I64 tests = 13;
  I64 correct = 0;

  if (TestDefineAlone()) correct++;
  else "Failed - TestDefineAlone()\n";

  if (TestDefineAfterClass()) correct++;
  else "Failed - TestDefineAfterClass()\n";

  if (TestDefineNotNumeric()) correct++;
  else "Failed - TestDefineNotNumeric()\n";

  if (TestDefineNothing()) correct++;
  else "Failed - TestDefineNothing()\n";

  if (TestIfCondition()) correct++;
  else "Failed - TestIfCondition()\n";

  if (TestElifCondition()) correct++;
  else "Failed - TestElifCondition()\n";

  if (TestIfExtraToken()) correct++;
  else "Failed - TestIfExtraToken()\n";

  if (TestIfEmpty()) correct++;
  else "Failed - TestIfEmpty()\n";

  if (TestUnterminatedIf()) correct++;
  else "Failed - TestUnterminatedIf()\n";

  if (TestUnknownDirective()) correct++;
  else "Failed - TestUnknownDirective()\n";

  if (TestIncludeSyntax()) correct++;
  else "Failed - TestIncludeSyntax()\n";

  if (TestUndefNothing()) correct++;
  else "Failed - TestUndefNothing()\n";

  if (TestLinkSyntax()) correct++;
  else "Failed - TestLinkSyntax()\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
