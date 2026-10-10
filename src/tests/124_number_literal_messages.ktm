#include "testhelper.ktm"

#define NUM_MSG_TEST_FILE "__124_number_messages.ktm"
#define NUM_MSG_TEST_OUT  "__124_number_messages.out"
#define NUM_MSG_TEST_BIN  "__124_number_messages.bin"

U0 CleanupNumMsgTest(U0)
{
  Rm(NUM_MSG_TEST_FILE);
  Rm(NUM_MSG_TEST_OUT);
  Rm(NUM_MSG_TEST_BIN);
}

/* Compile `source` with ktm: it must fail normally (exit status 1),
 * print `expected` as the reason and then "malformed numeric literal". */
Bool CompileFailsWith(U8 *source, U8 *expected)
{
  U8 *out, *reason, *err;
  I64 res;
  Bool ok = TRUE;

  CleanupNumMsgTest;

  if (!FileWrite(NUM_MSG_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " NUM_MSG_TEST_FILE " -o " NUM_MSG_TEST_BIN " > " NUM_MSG_TEST_OUT " 2>&1");
  if (res != 1) {
    "ktm exit status %d, expected a normal compile failure\n", res;
    ok = FALSE;
  } else {
    out = FileRead(NUM_MSG_TEST_OUT);
    reason = NULL;
    err = NULL;
    if (out) {
      reason = strstr(out, expected);
      err = strstr(out, "malformed numeric literal");
    }
    if (!reason || !err || reason > err) {
      if (out) "%s\n", out;
      ok = FALSE;
    }
    Free(out);
  }

  CleanupNumMsgTest;
  return ok;
}

/* A second `.` used to give no reason at all. */
Bool TestTwoDecimalPoints(U0)
{
  Bool ok = TRUE;
  if (!CompileFailsWith("U0 Main() { F64 x = 1.2.3; }\n",
                        "line 1: a number can only have one decimal point")) {
    "  1.2.3\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { F64 x = .5.3; }\n",
                        "a number can only have one decimal point")) {
    "  .5.3\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() {\n  F64 x = 0;\n  x = 10.25.5;\n}\n",
                        "line 3: a number can only have one decimal point")) {
    "  10.25.5 on line 3\n"; ok = FALSE;
  }
  return ok;
}

/* A `.` after the exponent used to give no reason either. */
Bool TestFractionalExponent(U0)
{
  Bool ok = TRUE;
  if (!CompileFailsWith("U0 Main() { F64 x = 1e3.5; }\n",
                        "the exponent of a number must be a whole number")) {
    "  1e3.5\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { F64 x = 1.5e-3.2; }\n",
                        "the exponent of a number must be a whole number")) {
    "  1.5e-3.2\n"; ok = FALSE;
  }
  return ok;
}

/* `0xx` said "seen x", `12x5` "invalid character 'x' in number". */
Bool TestStrayX(U0)
{
  Bool ok = TRUE;
  if (!CompileFailsWith("U0 Main() { I64 x = 0xx; }\n",
                        "'x' can only appear in the 0x prefix of a hex literal")) {
    "  0xx\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { I64 x = 0x1x; }\n",
                        "'x' can only appear in the 0x prefix of a hex literal")) {
    "  0x1x\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { I64 x = 0X1X; }\n",
                        "'X' can only appear in the 0x prefix of a hex literal")) {
    "  0X1X\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { I64 x = 12x5; }\n",
                        "'x' can only appear in the 0x prefix of a hex literal")) {
    "  12x5\n"; ok = FALSE;
  }
  return ok;
}

/* `0x.5` said "LEX error is float and ishex", `0x1.5.3` gave no reason,
 * `0b1.1` "invalid binary digit: '.'". */
Bool TestFractionalHexBinary(U0)
{
  Bool ok = TRUE;
  if (!CompileFailsWith("U0 Main() { F64 x = 0x.5; }\n",
                        "hex literals can't have a fractional part")) {
    "  0x.5\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { F64 x = 0x1.5; }\n",
                        "hex literals can't have a fractional part")) {
    "  0x1.5\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { F64 x = 0x1.5.3; }\n",
                        "hex literals can't have a fractional part")) {
    "  0x1.5.3\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { F64 x = 0b1.1; }\n",
                        "binary literals can't have a fractional part")) {
    "  0b1.1\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { F64 x = 0b.1; }\n",
                        "binary literals can't have a fractional part")) {
    "  0b.1\n"; ok = FALSE;
  }
  return ok;
}

I64 RangeCase(I64 i)
{
  switch (i) {
    case 1...3: return 1;
    case 0x4...0x6: return 2;
    case 0b111...0b1000: return 3;
    default: return 0;
  }
}

/* The valid neighbours still compile with their values. */
Bool TestValidLiterals(U0)
{
  Bool ok = TRUE;
  F64 a = 1.5, b = 1e10, c = 1.5e-3, d = 1f32, e = 2f64, f = .5;
  if (a != 1.5) { "  1.5 = %f\n", a; ok = FALSE; }
  if (b != 10000000000.0) { "  1e10 = %f\n", b; ok = FALSE; }
  if (c != 0.0015) { "  1.5e-3 = %f\n", c; ok = FALSE; }
  if (d != 1.0) { "  1f32 = %f\n", d; ok = FALSE; }
  if (e != 2.0) { "  2f64 = %f\n", e; ok = FALSE; }
  if (f != 0.5) { "  .5 = %f\n", f; ok = FALSE; }
  if (0x1e5 != 485) { "  0x1e5 = %d\n", 0x1e5; ok = FALSE; }
  if (0X1E5 != 485) { "  0X1E5 = %d\n", 0X1E5; ok = FALSE; }
  if (0xABC != 2748) { "  0xABC = %d\n", 0xABC; ok = FALSE; }
  if (0b101 != 5) { "  0b101 = %d\n", 0b101; ok = FALSE; }
  if (0x10U != 16) { "  0x10U = %d\n", 0x10U; ok = FALSE; }
  if (RangeCase(0) != 0 || RangeCase(1) != 1 || RangeCase(3) != 1 ||
      RangeCase(4) != 2 || RangeCase(6) != 2 || RangeCase(7) != 3 ||
      RangeCase(8) != 3 || RangeCase(9) != 0) {
    "  case ranges wrong\n"; ok = FALSE;
  }
  return ok;
}

I32 Main()
{
  "Test - number literal error messages: \n";
  I64 tests = 5;
  I64 correct = 0;

  if (TestTwoDecimalPoints()) correct++;
  else "Failed - TestTwoDecimalPoints()\n";

  if (TestFractionalExponent()) correct++;
  else "Failed - TestFractionalExponent()\n";

  if (TestStrayX()) correct++;
  else "Failed - TestStrayX()\n";

  if (TestFractionalHexBinary()) correct++;
  else "Failed - TestFractionalHexBinary()\n";

  if (TestValidLiterals()) correct++;
  else "Failed - TestValidLiterals()\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
