#include "testhelper.ktm"

#define NUM_SUFFIX_TEST_FILE "__103_number_literal_suffix.ktm"
#define NUM_SUFFIX_TEST_OUT  "__103_number_literal_suffix.out"
#define NUM_SUFFIX_TEST_BIN  "__103_number_literal_suffix.bin"

#define NUM_SUFFIX_HEX  0x1F
#define NUM_SUFFIX_EXPR (100+23)
#define NUM_SUFFIX_U    0x0800U

U0 CleanupNumSuffixTest(U0)
{
  Rm(NUM_SUFFIX_TEST_FILE);
  Rm(NUM_SUFFIX_TEST_OUT);
  Rm(NUM_SUFFIX_TEST_BIN);
}

/* Compile `source` with ktm: it must fail normally (exit status 1) and
 * print `expected`. */
Bool CompileFailsWith(U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;
  Bool ok = TRUE;

  CleanupNumSuffixTest;

  if (!FileWrite(NUM_SUFFIX_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " NUM_SUFFIX_TEST_FILE " -o " NUM_SUFFIX_TEST_BIN " > " NUM_SUFFIX_TEST_OUT " 2>&1");
  if (res != 1) {
    "ktm exit status %d, expected a normal compile failure\n", res;
    ok = FALSE;
  } else {
    out = FileRead(NUM_SUFFIX_TEST_OUT);
    if (!out || !strstr(out, expected) || !strstr(out, "malformed numeric literal")) {
      if (out) "%s\n", out;
      ok = FALSE;
    }
    Free(out);
  }

  CleanupNumSuffixTest;
  return ok;
}

Bool TestDecimalHexLetters(U0)
{
  return CompileFailsWith(
"U0 Main() {
  I64 x = 123abc;
  \"%d\\n\", x;
}
", "invalid character 'a' in number");
}

Bool TestDecimalOtherLetter(U0)
{
  return CompileFailsWith(
"U0 Main() { I64 x = 123g; }
", "invalid suffix 'g' on number");
}

Bool TestHexBadSuffix(U0)
{
  return CompileFailsWith(
"U0 Main() { I64 x = 0x12G; }
", "invalid suffix 'G' on number");
}

Bool TestBinaryBadDigit(U0)
{
  return CompileFailsWith(
"U0 Main() { I64 x = 0b102; }
", "invalid binary digit: '2'");
}

Bool TestXNotAfterZero(U0)
{
  return CompileFailsWith(
"U0 Main() { I64 x = 12x5; }
", "'x' can only appear in the 0x prefix of a hex literal");
}

Bool TestFloatLetters(U0)
{
  return CompileFailsWith(
"U0 Main() { F64 x = 1.5abc; }
", "invalid character 'a' in number");
}

Bool TestInMacro(U0)
{
  return CompileFailsWith(
"#define BAD 12ab
U0 Main() { I64 x = BAD; }
", "invalid character 'a' in number");
}

/* Valid literals: every form the lexer accepts must keep its value. */
Bool TestValidLiterals(U0)
{
  I64 arr[3] = {1, 2, 3};
  Bool ok = TRUE;
  if (123 != 100 + 23) ok = FALSE;
  if (0xABC != 2748) ok = FALSE;
  if (0xabcdef != 11259375) ok = FALSE;
  if (0XAB != 171) ok = FALSE;
  if (0x1E3 != 483) ok = FALSE;
  if (0xff32 != 65330) ok = FALSE;   /* `f32` here is hex digits, not a suffix */
  if (0xff64 != 65380) ok = FALSE;
  if (0b101 != 5) ok = FALSE;
  if (0B11 != 3) ok = FALSE;
  if (1.5 != 3.0 / 2) ok = FALSE;
  if (1e10 != 10000000000.0) ok = FALSE;
  if (1.5e-3 != 0.0015) ok = FALSE;
  if (1E2 != 100.0) ok = FALSE;
  if (1f32 != 1.0) ok = FALSE;
  if (2f64 != 2.0) ok = FALSE;
  if (2048U != 2048) ok = FALSE;
  if (0b11u != 3) ok = FALSE;
  if (NUM_SUFFIX_U != 2048) ok = FALSE;
  if (NUM_SUFFIX_HEX != 31) ok = FALSE;
  if (NUM_SUFFIX_EXPR != 123) ok = FALSE;
  if ((123) != 123 || -123 + 123 != 0) ok = FALSE;
  if (arr[2] != 3) ok = FALSE;
  switch (2) {
    case 1...3: break;
    default: ok = FALSE;
  }
  return ok;
}

I32 Main()
{
  "Test - number literal suffixes: \n";
  I64 tests = 8;
  I64 correct = 0;

  if (TestDecimalHexLetters()) correct++;
  else "Failed - TestDecimalHexLetters()\n";

  if (TestDecimalOtherLetter()) correct++;
  else "Failed - TestDecimalOtherLetter()\n";

  if (TestHexBadSuffix()) correct++;
  else "Failed - TestHexBadSuffix()\n";

  if (TestBinaryBadDigit()) correct++;
  else "Failed - TestBinaryBadDigit()\n";

  if (TestXNotAfterZero()) correct++;
  else "Failed - TestXNotAfterZero()\n";

  if (TestFloatLetters()) correct++;
  else "Failed - TestFloatLetters()\n";

  if (TestInMacro()) correct++;
  else "Failed - TestInMacro()\n";

  if (TestValidLiterals()) correct++;
  else "Failed - TestValidLiterals()\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
