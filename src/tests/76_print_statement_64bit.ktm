#include "testhelper.ktm"

/* A print statement, `"%d\n", x;`, compiles to a call to C's printf
 * where `%d` is a 32-bit int, so 5000000000 printed as 705032704. In
 * ktm `%d` is an I64: the format's integer conversions are now made
 * 64-bit and narrow integer arguments are widened to match. The
 * program is compiled both ahead of time and with the JIT and its
 * printed output compared. */

#define PRINT64_TEST_SRC "./ktm-76-print64.ktm"
#define PRINT64_TEST_BIN "./ktm-76-print64"
#define PRINT64_TEST_OUT "./ktm-76-print64.out"
/* A single literal: adjacent strings don't concatenate inside #define */
#define PRINT64_KTM "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U0 CleanupPrint64Test(U0)
{
  System("rm -f " PRINT64_TEST_SRC " " PRINT64_TEST_BIN " " PRINT64_TEST_OUT);
}

/* The captured output of the last run must be exactly `expected` */
Bool OutputIs(U8 *expected)
{
  U8 *out = FileRead(PRINT64_TEST_OUT);
  Bool ok;
  if (!out) return FALSE;
  ok = StrCmp(out, expected) == 0;
  if (!ok) "got: '%s'\n", out;
  Free(out);
  return ok;
}

U8 *print64_source =
"I16 RetI16(I64 v) { return v; }
U0 Main()
{
  I64 big = 5000000000;
  I64 m = -1;
  U8 u8 = 200;
  I32 i32 = -5;
  I16 i16 = -7;
  Bool b = TRUE;
  \"%d %i %u\\n\", big, -big, big;
  \"%x %X %o\\n\", big, big, big;
  \"%d %d %d %d %d\\n\", u8, i32, i16, b, RetI16(-70000);
  \"%d %u %x\\n\", m(I16), m(U32), m(U8);
  \"[%12d] [%-12d] [%012d] [%08X] [%+d]\\n\", big, big, big, 0xABC, 7;
  \"%ld %lld %lu\\n\", big, -big, big;
  \"%.3f %s %c %%d %d\\n\", 3.14159, \"str\", 'Z', -1;
  \"top %d\" \" level %d\\n\", big, big * 2;
}
";

U8 *print64_expected =
"5000000000 -5000000000 5000000000
12a05f200 12A05F200 45201371000
200 -5 -7 1 -4464
-1 4294967295 ff
[  5000000000] [5000000000  ] [005000000000] [00000ABC] [+7]
5000000000 -5000000000 5000000000
3.142 str Z %d -1
top 5000000000 level 10000000000
";

I32 Main()
{
  "Test - 64-bit print statements: ";
  I64 tests = 2;
  I64 correct = 0;

  CleanupPrint64Test;
  FileWrite(PRINT64_TEST_SRC, print64_source, StrLen(print64_source),
            O_CREAT | O_RDWR | O_TRUNC);

  if (System(PRINT64_KTM "-jit " PRINT64_TEST_SRC " > " PRINT64_TEST_OUT " 2>&1") == 0 &&
      OutputIs(print64_expected)) correct++;
  else "Failed - JIT output\n";

  if (System(PRINT64_KTM PRINT64_TEST_SRC " -o " PRINT64_TEST_BIN " && "
             PRINT64_TEST_BIN " > " PRINT64_TEST_OUT " 2>&1") == 0 &&
      OutputIs(print64_expected)) correct++;
  else "Failed - AOT output\n";

  CleanupPrint64Test;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
