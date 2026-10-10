#include "testhelper.ktm"

/* A file that ends in the middle of a construct is reported with an
 * "unexpected end of input" error and exit status 1. ktm used to loop
 * forever on `I64 g[3] = {1` (rewinding past the end of input handed
 * the previous token back again) and segfault on `I64 g[3] =`,
 * `extern`, `class C1 :` and `class N { I64 (*`, among others. */

#define EOF_TEST_FILE "__130_end_of_input.ktm"
#define EOF_TEST_OUT  "__130_end_of_input.out"

#define MSG_EOF "Unexpected end of input"

U0 CleanupEofTest(U0)
{
  Rm(EOF_TEST_FILE);
  Rm(EOF_TEST_OUT);
  Rm("./a.out");
}

/* Compile `source` (written without a trailing newline): ktm must exit
 * with status 1, not hang or crash, and print `expected`. `timeout`
 * guards against a hang where it exists. */
Bool CompileFailsAtEnd(U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;
  Bool ok;

  CleanupEofTest;

  if (!FileWrite(EOF_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("T=; command -v timeout >/dev/null 2>&1 && T='timeout 10'; "
               "$T ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               EOF_TEST_FILE " > " EOF_TEST_OUT " 2>&1");

  out = FileRead(EOF_TEST_OUT);
  if (!out) {
    CleanupEofTest;
    return FALSE;
  }

  /* System gives the exit code: 1 for a compile error; a hang is
   * 124 and a segfault 139 (from the shell). */
  ok = res == 1 && strstr(out, expected) != NULL;
  if (!ok) "`%s`: status %d\n%s\n", source, res, out;

  Free(out);
  CleanupEofTest;
  return ok;
}

I32 Main()
{
  "Test - End of input inside a construct: \n";
  I64 tests = 0;
  I64 correct = 0;

  /* The reported cases */
  U8 *cases[] = {
    "I64 g[3] = {1",
    "I64 g[3] = {1,",
    "I64 g[3] =",
    "extern",
    "class B {I64 x;}; class C1 :",
    "class N { I64 (*",
    /* After a type */
    "I64",
    "static I64 *",
    "U0 Main() { I64",
    "U0 Main() { I64 *",
    /* Statements */
    "U0 Main() { for (I64",
    "U0 Main() { I64 i; for (i = 0; i",
    "U0 Main() { if (1) {} else",
    "U0 Main() { do {}",
    "U0 Main() { \"%s\", typeof",
    "U0 F() { static U8",
    "inline",
    "inline I64",
    /* Calls */
    "I64 F(I64 a) { return a; } U0 Main() { F(",
    "U0 Main() { \"%d\", 1,",
    /* Classes and initialisers */
    "class U { union",
    "class P { I64 a; I64 b; }; P p = {1, 2",
    "U0 Main() { I64 a[2] = {{1}, {2"
  };
  I64 i, n = sizeof(cases) / sizeof(cases[0]);

  for (i = 0; i < n; i++) {
    tests++;
    if (CompileFailsAtEnd(cases[i], MSG_EOF)) correct++;
    else "Failed - case %d\n", i;
  }

  /* A string literal or #include path cut off by the end of input */
  tests++;
  if (CompileFailsAtEnd("U0 Main() { \"abc", "Unterminated string")) correct++;
  else "Failed - unterminated string\n";

  tests++;
  if (CompileFailsAtEnd("#include \".", "Unterminated string")) correct++;
  else "Failed - unterminated #include path\n";

  /* `defined(` cut off inside an #if condition */
  tests++;
  if (CompileFailsAtEnd("#if defined(", "#if: the condition must be")) correct++;
  else "Failed - #if defined(\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
