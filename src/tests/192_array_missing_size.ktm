#include "testhelper.ktm"

/* `U0 Main() { I64 a[; }` crashed ktm: the array size expression was
 * empty (parseExpr returned NULL at the `;`) and was evaluated anyway.
 * An array declaration with neither a size nor a `]` is now reported at
 * the token where the `]` was expected, like `I64 a[2;` already was.
 * Each source must fail with exit status 1 (not a signal or a hang). */

#define ARRSZ_TEST_SRC "./ktm-192-arrsize.ktm"
#define ARRSZ_TEST_OUT "./ktm-192-arrsize.out"
#define ARRSZ_TEST_BIN "./ktm-192-arrsize.bin"
/* A single literal: adjacent strings don't concatenate inside #define */
#define ARRSZ_KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U0 CleanupArrSizeTest(U0)
{
  System("rm -f " ARRSZ_TEST_SRC " " ARRSZ_TEST_OUT " " ARRSZ_TEST_BIN);
}

/* ktm exits 1 and its output has `e1` and `e2` (NULL to skip) */
Bool FailsWith(U8 *source, U8 *e1, U8 *e2 = NULL)
{
  U8 *out;
  I64 res;
  Bool ok;

  CleanupArrSizeTest;
  FileWrite(ARRSZ_TEST_SRC, source, StrLen(source),
            O_CREAT | O_RDWR | O_TRUNC);
  res = System(ARRSZ_KTM ARRSZ_TEST_SRC " -o " ARRSZ_TEST_BIN " > "
               ARRSZ_TEST_OUT " 2>&1; test $? -eq 1");
  out = FileRead(ARRSZ_TEST_OUT);
  ok = res == 0 && out && strstr(out, e1) && (!e2 || strstr(out, e2));
  if (!ok) "got (%d): '%s'\n", res, out;
  Free(out);
  CleanupArrSizeTest;
  return ok;
}

#define WANT_BRACKET "perhaps you meant `]`"

I32 Main()
{
  "Test - array declaration missing its size: ";
  I64 tests = 0;
  I64 correct = 0;

  /* The repro, reported at the `;` (line 3, column 9) */
  tests++;
  if (FailsWith("U0 Main()\n{\n  I64 a[;\n}\nMain;\n",
                WANT_BRACKET, "arrsize.ktm:3:9")) correct++;
  else "Failed - the repro\n";

  /* A global, a class field and a parameter */
  tests++;
  if (FailsWith("I64 g[;\nI64 h;\n", WANT_BRACKET, "arrsize.ktm:1:7"))
    correct++;
  else "Failed - global\n";
  tests++;
  if (FailsWith("class C\n{\n  I64 f[;\n};\n", WANT_BRACKET,
                "arrsize.ktm:3:9")) correct++;
  else "Failed - class field\n";
  tests++;
  if (FailsWith("U0 F(I64 p[;) {}\n", WANT_BRACKET)) correct++;
  else "Failed - parameter\n";

  /* The second dimension, and other tokens than `;` */
  tests++;
  if (FailsWith("U0 Main()\n{\n  I64 a[2][;\n}\nMain;\n",
                WANT_BRACKET, "arrsize.ktm:3:12")) correct++;
  else "Failed - a[2][\n";
  tests++;
  if (FailsWith("U0 Main()\n{\n  I64 a[) = 0;\n}\nMain;\n",
                "unexpected character `)`")) correct++;
  else "Failed - a[)\n";

  /* At the end of the file */
  tests++;
  if (FailsWith("I64 a[", "Unexpected end of input")) correct++;
  else "Failed - a[ at end of file\n";
  tests++;
  if (FailsWith("I64 a[2][", "Unexpected end of input")) correct++;
  else "Failed - a[2][ at end of file\n";

  /* A size that is there but not constant is still its own error */
  tests++;
  if (FailsWith("U0 Main(I64 n)\n{\n  I64 a[n];\n}\nMain(1);\n",
                "Expected constant integer expression")) correct++;
  else "Failed - a[n]\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
