#include "testhelper.ktm"

#define HEX_NO_DIGITS_TEST_FILE "__117_hex_no_digits.ktm"
#define HEX_NO_DIGITS_TEST_OUT  "__117_hex_no_digits.out"
#define HEX_NO_DIGITS_TEST_BIN  "__117_hex_no_digits.bin"

U0 CleanupHexNoDigitsTest(U0)
{
  Rm(HEX_NO_DIGITS_TEST_FILE);
  Rm(HEX_NO_DIGITS_TEST_OUT);
  Rm(HEX_NO_DIGITS_TEST_BIN);
}

/* Compile `source` with ktm: it must fail normally (exit status 1) and
 * print `expected`. */
Bool CompileFailsWith(U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;
  Bool ok = TRUE;

  CleanupHexNoDigitsTest;

  if (!FileWrite(HEX_NO_DIGITS_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " HEX_NO_DIGITS_TEST_FILE " -o " HEX_NO_DIGITS_TEST_BIN " > " HEX_NO_DIGITS_TEST_OUT " 2>&1");
  if (res != 1) {
    "ktm exit status %d, expected a normal compile failure\n", res;
    ok = FALSE;
  } else {
    out = FileRead(HEX_NO_DIGITS_TEST_OUT);
    if (!out || !strstr(out, expected) || !strstr(out, "malformed numeric literal")) {
      if (out) "%s\n", out;
      ok = FALSE;
    }
    Free(out);
  }

  CleanupHexNoDigitsTest;
  return ok;
}

/* `0x` with no digits used to be read as 0. */
Bool TestHexNoDigits(U0)
{
  Bool ok = TRUE;
  if (!CompileFailsWith("U0 Main() { I64 x = 0x; \"%d\\n\", x; }\n",
                        "hex literal has no digits")) {
    "  0x;\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { I64 x = 0X; }\n",
                        "hex literal has no digits")) {
    "  0X;\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { I64 x = (0x); }\n",
                        "hex literal has no digits")) {
    "  (0x)\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { I64 x = 0x+1; }\n",
                        "hex literal has no digits")) {
    "  0x+1\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { I64 x = 0xg; }\n",
                        "hex literal has no digits")) {
    "  0xg\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { I64 x = 0xU; }\n",
                        "hex literal has no digits")) {
    "  0xU\n"; ok = FALSE;
  }
  return ok;
}

/* `1f325` used to be read as 1.0, silently dropping the `5`. */
Bool TestDigitsAfterFloatSuffix(U0)
{
  Bool ok = TRUE;
  if (!CompileFailsWith("U0 Main() { F64 x = 1f325; \"%f\\n\", x; }\n",
                        "invalid suffix 'f325' on number")) {
    "  1f325\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { F64 x = 2f640; }\n",
                        "invalid suffix 'f640' on number")) {
    "  2f640\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { F64 x = 1.5f321; }\n",
                        "invalid suffix 'f321' on number")) {
    "  1.5f321\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { F64 x = 1f32.5; }\n",
                        "invalid suffix 'f32.5' on number")) {
    "  1f32.5\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { F64 x = 1f32e3; }\n",
                        "invalid suffix 'f32e3' on number")) {
    "  1f32e3\n"; ok = FALSE;
  }
  if (!CompileFailsWith("U0 Main() { F64 x = 1f32g; }\n",
                        "invalid suffix 'g' on number")) {
    "  1f32g\n"; ok = FALSE;
  }
  return ok;
}

/* The valid neighbours keep their values. */
Bool TestValidLiterals(U0)
{
  Bool ok = TRUE;
  F64 a = 1f32, b = 2f64, c = 1.5f32, d = 1e3f64, e = -1f32*2;
  if (a != 1.0) { "  1f32 = %f\n", a; ok = FALSE; }
  if (b != 2.0) { "  2f64 = %f\n", b; ok = FALSE; }
  if (c != 1.5) { "  1.5f32 = %f\n", c; ok = FALSE; }
  if (d != 1000.0) { "  1e3f64 = %f\n", d; ok = FALSE; }
  if (e != -2.0) { "  -1f32*2 = %f\n", e; ok = FALSE; }
  if (1f32+1 != 2.0) ok = FALSE;
  if (0xff32 != 65330) { "  0xff32 = %d\n", 0xff32; ok = FALSE; }
  if (0xff64 != 65380) { "  0xff64 = %d\n", 0xff64; ok = FALSE; }
  if (0x1f32 != 7986) ok = FALSE;
  if (0x0 != 0 || 0X0 != 0 || 0x00 != 0) ok = FALSE;
  if (0xF != 15 || 0x10U != 16) ok = FALSE;
  return ok;
}

I32 Main()
{
  "Test - hex literal without digits, float suffix: \n";
  I64 tests = 3;
  I64 correct = 0;

  if (TestHexNoDigits()) correct++;
  else "Failed - TestHexNoDigits()\n";

  if (TestDigitsAfterFloatSuffix()) correct++;
  else "Failed - TestDigitsAfterFloatSuffix()\n";

  if (TestValidLiterals()) correct++;
  else "Failed - TestValidLiterals()\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
