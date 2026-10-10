#include "testhelper.ktm"

/* ktm syntax v0.2: `_` may appear between two digits of a number
 * literal (its own base: decimal, or hex inside a hex literal) and is
 * ignored: 1_000_000, 0xFF_FF, 0b1010_0101, 1_000.25, 1_0e1_0. Misplaced
 * at the start or end of the digits, next to `.` or the exponent, or
 * doubled, it is an error: "misplaced '_' in number". A leading `_`
 * is still an identifier. Also works in #define bodies and #if. ktm
 * has no octal literals, so there is no 0o form to separate. */

#define SEP_TEST_FILE "__416_digit_sep.ktm"
#define SEP_TEST_OUT  "__416_digit_sep.out"
#define SEP_TEST_BIN  "__416_digit_sep.bin"

U0 CleanupSepTest(U0)
{
  Rm(SEP_TEST_FILE);
  Rm(SEP_TEST_OUT);
  Rm(SEP_TEST_BIN);
}

/* Compile `source` with ktm: it must fail normally (exit status 1)
 * and print "misplaced '_' in number". */
Bool CompileFailsWithMisplaced(U8 *source)
{
  U8 *out;
  I64 res;
  Bool ok = TRUE;

  CleanupSepTest;

  if (!FileWrite(SEP_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " SEP_TEST_FILE " -o " SEP_TEST_BIN " > " SEP_TEST_OUT " 2>&1");
  if (res != 1) {
    "ktm exit status %d, expected a normal compile failure\n", res;
    ok = FALSE;
  } else {
    out = FileRead(SEP_TEST_OUT);
    if (!out || !strstr(out, "misplaced '_' in number")) {
      if (out) "%s\n", out;
      ok = FALSE;
    }
    Free(out);
  }

  CleanupSepTest;
  return ok;
}

#define BIG 1_000_000
#if BIG > 999_999
I64 g_from_define = BIG;
#else
I64 g_from_define = -1;
#endif

I64 RangeCase(I64 i)
{
  switch (i) {
    case 1_0...3_0: return 1;
    default:        return 0;
  }
}

/* The valid forms, each compared to the plain literal. */
Bool TestValidLiterals(U0)
{
  Bool ok = TRUE;
  I64 a = 1_000_000, b = 0xFF_FF, c = 0b1010_0101, d = 0x1_0U;
  F64 e = 1_000.25, f = 1_0e1_0, g = 1.5_0e-1_0;

  if (a != 1000000) { "  1_000_000 = %d\n", a; ok = FALSE; }
  if (b != 0xFFFF) { "  0xFF_FF = %d\n", b; ok = FALSE; }
  if (c != 0b10100101) { "  0b1010_0101 = %d\n", c; ok = FALSE; }
  if (d != 0x10) { "  0x1_0U = %d\n", d; ok = FALSE; }
  if (e != 1000.25) { "  1_000.25 = %f\n", e; ok = FALSE; }
  if (f != 1.0e11) { "  1_0e1_0 = %f\n", f; ok = FALSE; }
  if (g != 1.5e-10) { "  1.5_0e-1_0 = %.20f\n", g; ok = FALSE; }
  if (g_from_define != 1000000) { "  #define BIG = %d\n", g_from_define; ok = FALSE; }
  if (RangeCase(20) != 1 || RangeCase(5) != 0) { "  case ranges wrong\n"; ok = FALSE; }
  return ok;
}

/* A leading `_` is still an identifier, not a number. */
I64 UnderscoreName(U0)
{
  I64 _100 = 7;
  return _100;
}

/* Every misplaced form. */
Bool TestMisplaced(U0)
{
  Bool ok = TRUE;
  U8 *forms[13];
  I64 i, n = 0;

  forms[n++] = "I32 Main() { I64 x = 0x_FF; }\n";       /* start, hex */
  forms[n++] = "I32 Main() { I64 x = 100_; }\n";         /* end */
  forms[n++] = "I32 Main() { F64 x = 1_.5; }\n";         /* before '.' */
  forms[n++] = "I32 Main() { F64 x = 1._5; }\n";         /* after '.' */
  forms[n++] = "I32 Main() { F64 x = 1e_5; }\n";         /* after 'e' */
  forms[n++] = "I32 Main() { F64 x = 1_e5; }\n";         /* before 'e' */
  forms[n++] = "I32 Main() { I64 x = 1__0; }\n";         /* doubled */
  forms[n++] = "I32 Main() { I64 x = 0b_101; }\n";       /* start, binary */
  forms[n++] = "I32 Main() { I64 x = 0b101_; }\n";       /* end, binary */
  forms[n++] = "I32 Main() { I64 x = 0xFF__FF; }\n";     /* doubled, hex */

  for (i = 0; i < n; i++) {
    if (!CompileFailsWithMisplaced(forms[i])) {
      "  form %d: %s", i, forms[i];
      ok = FALSE;
    }
  }
  return ok;
}

I32 Main()
{
  "Test - digit separators: \n";
  I64 tests = 3;
  I64 correct = 0;

  if (TestValidLiterals()) correct++;
  else "Failed - TestValidLiterals()\n";

  if (UnderscoreName() == 7) correct++;
  else "Failed - UnderscoreName()\n";

  if (TestMisplaced()) correct++;
  else "Failed - TestMisplaced()\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
