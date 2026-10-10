#include "testhelper.ktm"

#define BINOP_NOISE_FILE "__131_binop_type_error_noise.ktm"
#define BINOP_NOISE_OUT  "__131_binop_type_error_noise.out"

U0 CleanupBinopNoiseTest(U0)
{
  Rm(BINOP_NOISE_FILE);
  Rm(BINOP_NOISE_OUT);
  Rm("./a.out");
}

I64 CountOccurrences(U8 *haystack, U8 *needle)
{
  I64 count = 0;
  U8 *p = haystack;
  while ((p = strstr(p, needle)) != NULL) {
    count++;
    p += StrLen(needle);
  }
  return count;
}

/* Compile `source`. `want_status` is the expected exit status (0 or 1).
 * The output must contain each of the `nwant` strings in `want`, exactly
 * `nerrors` "error:" lines, and none of the location-less warnings that
 * the type inference used to print for an operator whose operand types
 * don't combine ("Binop has returned null type" / "Binary type is being
 * assigned as NULL"). */
Bool CompileCheck(U8 *source, I64 want_status, I64 nerrors, U8 **want, I64 nwant)
{
  U8 *out;
  I64 res, i;
  Bool ok = TRUE;

  CleanupBinopNoiseTest;

  if (!FileWrite(BINOP_NOISE_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  /* 7 = exited 1, 8 = exited 0, 9 = anything else (e.g. a crash) */
  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " BINOP_NOISE_FILE " > " BINOP_NOISE_OUT " 2>&1; s=$?; exit $(( s == 1 ? 7 : (s == 0 ? 8 : 9) ))");
  if (want_status == 1 && res != 7) ok = FALSE;
  if (want_status == 0 && res != 8) ok = FALSE;

  out = FileRead(BINOP_NOISE_OUT);
  if (!out) {
    CleanupBinopNoiseTest;
    return FALSE;
  }

  if (strstr(out, "Binop has returned null type")) ok = FALSE;
  if (strstr(out, "being assigned as NULL")) ok = FALSE;
  if (strstr(out, "WARNING")) ok = FALSE;
  if (CountOccurrences(out, "error:") != nerrors) ok = FALSE;
  for (i = 0; i < nwant; i++) {
    if (!strstr(out, want[i])) ok = FALSE;
  }
  if (!ok) "status code %d\n%s\n", res, out;

  Free(out);
  CleanupBinopNoiseTest;
  return ok;
}

/* The original report: a float or a string mixed with a string in a
 * #define printed both warnings (twice) before the located error. */
Bool TestDefineFloatString(U0)
{
  U8 *want[4];
  want[0] = "error: #define X";
  want[1] = " --> " BINOP_NOISE_FILE ":1:";
  want[2] = "error: #define Y";
  want[3] = " --> " BINOP_NOISE_FILE ":2:";
  return CompileCheck(
"#define X (2.5 + \"s\")
#define Y (\"s\" * 2)
U0 Main() {}
", 1, 2, want, 4);
}

/* The same expressions in a function: one located type error each. */
Bool TestLocalInit(U0)
{
  U8 *want[4];
  want[0] = "error: The `+` operator cannot be applied to a float type with a string literal of array type";
  want[1] = " --> " BINOP_NOISE_FILE ":2:15";
  want[2] = "error: The `*` operator cannot be applied to a array type with a literal of integer type";
  want[3] = " --> " BINOP_NOISE_FILE ":3:15";
  return CompileCheck(
"U0 Main() {
  I64 x = 2.5 + \"s\";
  I64 y = \"s\" * 2;
}
", 1, 2, want, 4);
}

/* Plain assignment, a comparison and a non-intrinsic class operand. */
Bool TestExprForms(U0)
{
  U8 *want[6];
  want[0] = " --> " BINOP_NOISE_FILE ":5:9";
  want[1] = "`F64 + U8[2]` is invalid";
  want[2] = " --> " BINOP_NOISE_FILE ":6:9";
  want[3] = "`F64 < U8[2]` is invalid";
  want[4] = " --> " BINOP_NOISE_FILE ":7:13";
  want[5] = "`Pt * I64` is invalid";
  return CompileCheck(
"class Pt { I64 x; };
U0 Main() {
  Pt p;
  F64 z = 1.5;
  z = z + \"q\";
  if (z < \"s\") \"no\\n\";
  I64 k = p * 2;
}
", 1, 3, want, 6);
}

/* A global initialiser goes through the same path. */
Bool TestGlobalInit(U0)
{
  U8 *want[2];
  want[0] = "error: The `+` operator cannot be applied to a float type with a string literal of array type";
  want[1] = " --> " BINOP_NOISE_FILE ":1:13";
  return CompileCheck(
"F64 g = 2.5 + \"s\";
U0 Main() {}
", 1, 1, want, 2);
}

/* Valid mixed-type operations still compile, without warnings. */
Bool TestValidStillCompiles(U0)
{
  return CompileCheck(
"#define OK_F (2.5 + 1)
#define OK_S \"a\" \"b\"
U0 Main() {
  F64 f = 2 + 1.5;
  U8 *s = \"abc\" + 1;
  I64 c = f < 3.0;
  \"%d %d\\n\", c, *s;
}
", 0, 0, NULL, 0);
}

I32 Main()
{
  "Test - Binop type error noise: \n";
  I64 tests = 5;
  I64 correct = 0;

  if (TestDefineFloatString()) correct++;
  else "Failed - TestDefineFloatString()\n";

  if (TestLocalInit()) correct++;
  else "Failed - TestLocalInit()\n";

  if (TestExprForms()) correct++;
  else "Failed - TestExprForms()\n";

  if (TestGlobalInit()) correct++;
  else "Failed - TestGlobalInit()\n";

  if (TestValidStillCompiles()) correct++;
  else "Failed - TestValidStillCompiles()\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
