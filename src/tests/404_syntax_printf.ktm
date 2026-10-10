#include "testhelper.ktm"

/* ktm syntax v0.2: printf(fmt, ...) is ktm's print, the same as the print
 * statement "fmt", ...; : %d/%x/%u print the full 64-bit value (narrow
 * integers are widened), a format using ktm's conversions (%Q %D %T %C %F)
 * goes through Print, and the arguments are checked against a literal
 * format at compile time. A snippet prints every case both ways; the two
 * outputs must match line for line. */

#define KTM "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U8 *both =
"I64 big = 5000000000;\n"
"I32 neg = -3;\n"
"U8 byte = 200;\n"
"I16 sh = -2;\n"
"F64 pi = 3.14159;\n"
"CDate day = 0;\n"
"printf(\"top level %d\\n\", 1);\n"
"\"top level %d\\n\", 1;\n"
"U0 Main()\n"
"{\n"
"  printf(\"%d %d %u %d\\n\", big, neg, byte, sh);\n"
"  \"%d %d %u %d\\n\", big, neg, byte, sh;\n"
"  printf(\"%x %X %o\\n\", big, 255, 8);\n"
"  \"%x %X %o\\n\", big, 255, 8;\n"
"  printf(\"%s|%5.2f|%c|%%\\n\", \"str\", pi, 'A');\n"
"  \"%s|%5.2f|%c|%%\\n\", \"str\", pi, 'A';\n"
"  printf(\"%Q\\n\", \"a\\\"b\");\n"
"  \"%Q\\n\", \"a\\\"b\";\n"
"  printf(\"%D\\n\", day);\n"
"  \"%D\\n\", day;\n"
"  printf(\"no arguments\\n\");\n"
"  \"no arguments\\n\";\n"
"  if (big > 0) printf(\"in if %d\\n\", 2);\n"
"  if (big > 0) \"in if %d\\n\", 2;\n"
"  I64 n = printf(\"%d\\n\", 42);\n"
"  printf(\"count %d\\n\", n);\n"
"  \"count %d\\n\", 3;\n"
"}\n";

/* Split s at each newline, in place: up to max lines into out. */
I64 Lines(U8 *s, U8 **out, I64 max)
{
  I64 n = 0;
  if (!s) return 0;
  while (*s && n < max) {
    out[n++] = s;
    while (*s && *s != '\n') s++;
    if (*s) *s++ = 0;
  }
  return n;
}

I32 Main()
{
  I64 correct = 0, tests = 0, i;
  "Test - printf: ";

  FileWrite("__404_both.ktm", both, StrLen(both));
  U8 *out = Shlurp(KTM "-jit __404_both.ktm 2>&1");
  U8 *aot = Shlurp(KTM "__404_both.ktm -o __404_both 2>&1 && ./__404_both");

  tests++;   /* each printf line equals the print statement after it */
  I64 pairs_ok = 1;
  U8 *copy = StrNew(out), *lines[32];
  I64 count = Lines(copy, lines, 32);
  if (count == 19) {
    /* 8 pairs, then the 42 whose length printf returns, then 2 counts */
    for (i = 0; i < 16; i += 2) if (StrCmp(lines[i], lines[i + 1])) pairs_ok = 0;
    if (StrCmp(lines[16], "42") || StrCmp(lines[17], "count 3") || StrCmp(lines[18], "count 3"))
      pairs_ok = 0;
  } else {
    pairs_ok = 0;
  }
  if (pairs_ok) correct++;
  else "\n  printf and the print statement differ (jit):\n%s\n", out;

  tests++;   /* the 64-bit, narrow, Q and D lines have the right text */
  if (count == 19 && !StrCmp(lines[0], "top level 1") &&
      !StrCmp(lines[2], "5000000000 -3 200 -2") && !StrCmp(lines[4], "12a05f200 FF 10") &&
      !StrCmp(lines[6], "str| 3.14|A|%") && !StrCmp(lines[8], "a\\\"b")) correct++;
  else "\n  printf output: [%s]\n", out;

  tests++;   /* compiled, the same output */
  if (out && aot && !StrCmp(out, aot)) correct++;
  else "\n  aot differs:\n%s\n", aot;

  tests++;   /* the arguments are checked against the format */
  U8 *bad = "U0 Main()\n{\n  printf(\"%s %d\\n\", \"x\");\n}\n";
  FileWrite("__404_bad.ktm", bad, StrLen(bad));
  U8 *err = Shlurp(KTM "-jit __404_bad.ktm 2>&1");
  if (err && strstr(err, "printf format expects 2 arguments but 1 was supplied") &&
      strstr(err, "__404_bad.ktm:3:")) correct++;
  else "\n  format check: [%s]\n", err;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
