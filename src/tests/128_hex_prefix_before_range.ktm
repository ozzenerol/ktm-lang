#include "testhelper.ktm"

/* `case 0x...3:` was accepted as `case 0...3:`: the lexer ended a number
 * at a `..` range operator by returning early, skipping the "hex literal
 * has no digits" check that `0x;` gets. A bare prefix before `...` must
 * be the same error, and real ranges must keep working. */

#define HEX_RANGE_TEST_FILE "__128_hex_range.ktm"
#define HEX_RANGE_TEST_OUT  "__128_hex_range.out"
#define HEX_RANGE_TEST_BIN  "__128_hex_range.bin"

U0 CleanupHexRangeTest(U0)
{
  Rm(HEX_RANGE_TEST_FILE);
  Rm(HEX_RANGE_TEST_OUT);
  Rm(HEX_RANGE_TEST_BIN);
}

/* Compile `source` with ktm: it must fail normally (exit status 1),
 * print `expected` and then "malformed numeric literal". */
Bool CompileFailsWith(U8 *source, U8 *expected)
{
  U8 *out, *reason, *err;
  I64 res;
  Bool ok = TRUE;

  CleanupHexRangeTest;

  if (!FileWrite(HEX_RANGE_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " HEX_RANGE_TEST_FILE " -o " HEX_RANGE_TEST_BIN " > " HEX_RANGE_TEST_OUT " 2>&1");
  if (res != 1) {
    "ktm exit status %d, expected a normal compile failure\n", res;
    ok = FALSE;
  } else {
    out = FileRead(HEX_RANGE_TEST_OUT);
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

  CleanupHexRangeTest;
  return ok;
}

U8 *Classify(I64 i)
{
  switch (i) {
    case 0x4...0x6:   return "hex";
    case 1...3:       return "dec";
    case 0b111...0b1001: return "bin";
    case 0xA...0xF:   return "hexletters";
    case 0x1E...0x20: return "hexe";
    case 0x21...40:   return "mixed";
    default:          return "none";
  }
}

I32 Main()
{
  I64 correct = 0, tests = 13;

  "Test - hex prefix before a case range: ";

  if (CompileFailsWith(
"U0 Main() { I64 i = 2; switch (i) { case 0x...3: \"in\\n\"; break; default: \"out\\n\"; } }\n",
        "line 1: hex literal has no digits")) correct++;
  if (CompileFailsWith(
"U0 Main() { I64 i = 2; switch (i) { case 0X...3: break; } }\n",
        "hex literal has no digits")) correct++;
  if (CompileFailsWith(
"U0 Main() { I64 i = 2; switch (i) { case 1...0x: break; } }\n",
        "hex literal has no digits")) correct++;
  if (CompileFailsWith(
"U0 Main() { I64 i = 2; switch (i) { case 0b...3: break; } }\n",
        "binary literal has no digits")) correct++;
  /* The same message as `0x` on its own. */
  if (CompileFailsWith("U0 Main() { I64 i = 0x; }\n",
        "hex literal has no digits")) correct++;

  if (!StrCmp(Classify(2), "dec") && !StrCmp(Classify(0), "none")) correct++;
  if (!StrCmp(Classify(4), "hex") && !StrCmp(Classify(6), "hex")) correct++;
  if (!StrCmp(Classify(7), "bin") && !StrCmp(Classify(9), "bin")) correct++;
  if (!StrCmp(Classify(10), "hexletters") && !StrCmp(Classify(15), "hexletters")) correct++;
  if (!StrCmp(Classify(30), "hexe") && !StrCmp(Classify(32), "hexe")) correct++;
  if (!StrCmp(Classify(33), "mixed") && !StrCmp(Classify(40), "mixed")) correct++;
  if (!StrCmp(Classify(16), "none") && !StrCmp(Classify(41), "none")) correct++;
  /* Ranges stop at the `...`, not at a float: `1.5` still lexes. */
  if (1.5 + 0x4 == 5.5) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
