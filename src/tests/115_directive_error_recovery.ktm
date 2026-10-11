#include "testhelper.ktm"

#define DIR_ERR_FILE "__115_directive_error.ktm"
#define DIR_ERR_OUT  "__115_directive_error.out"

#define DIR_MSG_IF_CONST "#if: the condition must be a constant expression made of literals, macros and defined()"

/* A good #if right after a class must keep working */
class DirErrOk { I64 a; };
#if 1 + 1 == 2
#define DIR_ERR_OK 3
#endif

U0 CleanupDirErrTest(U0)
{
  Rm(DIR_ERR_FILE);
  Rm(DIR_ERR_OUT);
  Rm("./a.out");
}

I64 CountErrors(U8 *out)
{
  I64 n = 0;
  U8 *p = out;
  while ((p = strstr(p, "error: "))) {
    n++;
    p += 7;
  }
  return n;
}

/* Compile `source`, which must fail with exactly `n_expected` errors,
 * `msg` (when not NULL) among them. */
Bool CompileFailsExactly(U8 *source, I64 n_expected, U8 *msg)
{
  U8 *out;
  I64 res;
  Bool ok;

  CleanupDirErrTest;

  if (!FileWrite(DIR_ERR_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " DIR_ERR_FILE " > " DIR_ERR_OUT " 2>&1");
  if (res == 0) {
    CleanupDirErrTest;
    return FALSE;
  }

  out = FileRead(DIR_ERR_OUT);
  if (!out) {
    CleanupDirErrTest;
    return FALSE;
  }

  ok = CountErrors(out) == n_expected;
  if (msg && !strstr(out, msg)) ok = FALSE;
  if (!ok) "%s\n", out;

  Free(out);
  CleanupDirErrTest;
  return ok;
}

/* Run `bad` after a class, after a union and with nothing before it;
 * each must give `msg` and nothing else. Foo is used afterwards, so a
 * class left half-declared by the error (which used to crash the
 * compiler) or a spurious error from its tail fails the case. */
Bool ErrorOnlyEverywhere(U8 *name, U8 *bad, U8 *msg)
{
  U8 *src;
  Bool ok = TRUE;

  src = MStrPrint("class Foo { I64 a; };\n%sU0 Main() { Foo f; f.a = 1; }\n", bad);
  if (!CompileFailsExactly(src, 1, msg)) {
    "Failed - %s after a class\n", name;
    ok = FALSE;
  }
  Free(src);

  src = MStrPrint("union Foo { I64 a; U8 b; };\n%sU0 Main() { Foo f; f.a = 1; }\n", bad);
  if (!CompileFailsExactly(src, 1, msg)) {
    "Failed - %s after a union\n", name;
    ok = FALSE;
  }
  Free(src);

  src = MStrPrint("%sclass Foo { I64 a; };\nU0 Main() { Foo f; f.a = 1; }\n", bad);
  if (!CompileFailsExactly(src, 1, msg)) {
    "Failed - %s with nothing before it\n", name;
    ok = FALSE;
  }
  Free(src);
  return ok;
}

Bool TestIfBadCondition(U0)
{
  return ErrorOnlyEverywhere("#if (zz+", "#if (zz+\nI64 y;\n#endif\n",
                             DIR_MSG_IF_CONST);
}

Bool TestElifBadCondition(U0)
{
  return ErrorOnlyEverywhere("#elif (1+", "#if 0\n#elif (1+\nI64 y;\n#endif\n",
      "#elif: the condition must be a constant expression made of literals, macros and defined()");
}

Bool TestIfEmpty(U0)
{
  return ErrorOnlyEverywhere("#if", "#if\nI64 y;\n#endif\n",
                             "a #if must evaluate some expression");
}

Bool TestIfExtraToken(U0)
{
  return ErrorOnlyEverywhere("#if 1 2", "#if 1 2\nI64 y;\n#endif\n",
                             "#if: unexpected '2' after the condition");
}

Bool TestIfBadBackslash(U0)
{
  return ErrorOnlyEverywhere("#if 1 \\ 2", "#if 1 \\ 2\nI64 y;\n#endif\n",
                             "Invalid use of '\\' should be");
}

Bool TestIncludeBadSyntax(U0)
{
  return ErrorOnlyEverywhere("#include 42", "#include 42\n",
                             "Syntax is: #include \"<value>\" got: TK_I64");
}

Bool TestIncludeMissingFile(U0)
{
  return ErrorOnlyEverywhere("#include missing",
                             "#include \"__115_no_such_file.ktm\"\n",
                             "#include: cannot open '__115_no_such_file.ktm'");
}

Bool TestLinkBadSyntax(U0)
{
  return ErrorOnlyEverywhere("#link 42", "#link 42\n",
                             "Syntax is: #link \"<path>\" or #link <libname> got: TK_I64");
}

Bool TestUndefBadSyntax(U0)
{
  return ErrorOnlyEverywhere("#undef 42", "#undef 42\n",
                             "Syntax is: #undef <TK_IDENT>");
}

/* `#undef` with nothing after it must not swallow the next line */
Bool TestUndefNothing(U0)
{
  return ErrorOnlyEverywhere("#undef", "#undef\nI64 y;\n",
                             "Syntax is: #undef <TK_IDENT>");
}

/* nor take the next line's first word when it is an identifier
 * (`void` is one) */
Bool TestUndefNothingVoid(U0)
{
  return ErrorOnlyEverywhere("#undef", "#undef\nvoid F() {}\n",
                             "Syntax is: #undef <TK_IDENT>");
}

Bool TestError(U0)
{
  return ErrorOnlyEverywhere("#error", "#error \"stop here\" and more\n",
                             "error: \"stop here\" and more\n");
}

Bool TestUnterminatedIf(U0)
{
  return CompileFailsExactly(
"class Foo { I64 a; };
#if 0
I64 y;
", 1, "Unterminated #if");
}

Bool TestBadNumber(U0)
{
  return ErrorOnlyEverywhere("0b12", "I64 q = 0b12;\n",
                             "malformed numeric literal");
}

Bool TestLongCharConst(U0)
{
  return ErrorOnlyEverywhere("'abcdefghij'", "I64 c = 'abcdefghij';\n",
                             "Char const limited to 8 characters");
}

Bool TestBadEscape(U0)
{
  return ErrorOnlyEverywhere("\"\\q\"", "U8 *s = \"\\q\";\n",
                             "Invalid escape character: '\\q'");
}

Bool TestDotDot(U0)
{
  return ErrorOnlyEverywhere("..", "U0 F(I64 a, ..) {}\n",
                             "`..` is an invalid token sequence");
}

/* A failed #if condition is taken as false: the #else branch is used,
 * so `y` is declared and Main has no error of its own. */
Bool TestIfFailedTakesElse(U0)
{
  return CompileFailsExactly(
"class Foo { I64 a; };
#if (zz+
I64 y = 1;
#else
I64 y = 2;
#endif
U0 Main() { Foo f; f.a = y; }
", 1, DIR_MSG_IF_CONST);
}

/* Several bad directives in a row are each reported once */
Bool TestSeveral(U0)
{
  return CompileFailsExactly(
"class Foo { I64 a; };
#if (zz+
#endif
#undef 1
union Bar { I64 a; U8 b; };
#include 7
class Baz { I64 b; };
U0 Main() { Foo f; Bar b; Baz z; f.a = 1; b.a = 2; z.b = 3; }
", 3, "Syntax is: #include");
}

I32 Main()
{
  "Test - directive errors after a class: \n";
  I64 tests = 20;
  I64 correct = 0;

  if (TestIfBadCondition()) correct++;
  if (TestElifBadCondition()) correct++;
  if (TestIfEmpty()) correct++;
  if (TestIfExtraToken()) correct++;
  if (TestIfBadBackslash()) correct++;
  if (TestIncludeBadSyntax()) correct++;
  if (TestIncludeMissingFile()) correct++;
  if (TestLinkBadSyntax()) correct++;
  if (TestUndefBadSyntax()) correct++;
  if (TestUndefNothing()) correct++;
  if (TestUndefNothingVoid()) correct++;
  if (TestError()) correct++;
  if (TestBadNumber()) correct++;
  if (TestLongCharConst()) correct++;
  if (TestBadEscape()) correct++;
  if (TestDotDot()) correct++;

  if (TestUnterminatedIf()) correct++;
  else "Failed - TestUnterminatedIf()\n";

  if (TestIfFailedTakesElse()) correct++;
  else "Failed - TestIfFailedTakesElse()\n";

  if (TestSeveral()) correct++;
  else "Failed - TestSeveral()\n";

  DirErrOk ok;
  ok.a = DIR_ERR_OK;
  if (ok.a == 3) correct++;
  else "Failed - #if after a class\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
