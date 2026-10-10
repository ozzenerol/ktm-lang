#include "testhelper.ktm"

/* Array types were named with their size in bytes (`I64 arr[2]` was
 * `I64[16]`, `I64 g[2][3]` was `I64[24][48]`), and a string literal's type
 * counted its escapes as written (`"a\tb"` was `U8[5]`). Types now show
 * the element counts as declared, and a string the bytes it holds. */

#define AT_TEST_FILE "__171_array_type_names.ktm"
#define AT_TEST_OUT  "__171_array_type_names.out"

class P
{
  I64 x, y;
};

U0 CleanupAtTest()
{
  Rm(AT_TEST_FILE);
  Rm(AT_TEST_OUT);
  Rm("./a.out");
}

Bool OutputHas(U8 *src, U8 *expected)
{
  U8 *out;
  Bool ok = FALSE;
  CleanupAtTest;
  if (FileWrite(AT_TEST_FILE, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC)) {
    System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " AT_TEST_FILE " > " AT_TEST_OUT " 2>&1");
    out = FileRead(AT_TEST_OUT);
    if (out) {
      ok = strstr(out, expected) != NULL;
      if (!ok) "%s\n", out;
      Free(out);
    }
  }
  CleanupAtTest;
  return ok;
}

I32 Main()
{
  "Test - array type names: ";
  I64 correct = 0, tests = 0;

  I64 arr[2];
  I64 g[2][3];
  P ps[4];
  U8 buf[10];
  F64 fs[3][1][2];

  tests++; if (!StrCmp(typeof(arr), "i64[2]")) correct++;
  tests++; if (!StrCmp(typeof(g), "i64[2][3]")) correct++;
  tests++; if (!StrCmp(typeof(g[1]), "i64[3]")) correct++;
  tests++; if (!StrCmp(typeof(ps), "P[4]")) correct++;
  tests++; if (!StrCmp(typeof(buf), "u8[10]")) correct++;
  tests++; if (!StrCmp(typeof(fs), "f64[3][1][2]")) correct++;
  tests++; if (sizeof(arr) == 16 && sizeof(g) == 48 && sizeof(g[1]) == 24) correct++;

  /* String literals: the bytes they hold, NUL included */
  tests++; if (!StrCmp(typeof("abc"), "u8[4]")) correct++;
  tests++; if (!StrCmp(typeof("a\tb\n"), "u8[5]")) correct++;
  tests++; if (!StrCmp(typeof("\\\""), "u8[3]")) correct++;
  tests++; if (!StrCmp(typeof(""), "u8[1]")) correct++;
  tests++; if (sizeof("a\tb\n") == 5) correct++;
  /* The type agrees with sizeof for \x escapes too */
  U8 *hex_type = MStrPrint("u8[%d]", sizeof("\x42\tq"));
  tests++; if (!StrCmp(typeof("\x42\tq"), hex_type)) correct++;
  Free(hex_type);

  /* Diagnostics */
  tests++;
  if (OutputHas("void Main()\n{\n  i64 arr[2];\n  f64 f = arr;\n}\n",
                "'i64[2]' is not assignable to type 'f64'")) correct++;
  tests++;
  if (OutputHas("void Main()\n{\n  i64 g[2][3];\n  f64 f = g;\n}\n",
                "'i64[2][3]' is not assignable to type 'f64'")) correct++;
  tests++;
  if (OutputHas("void Main()\n{\n  f64 f = 1.0;\n  f = f + \"a\\tb\";\n}\n",
                "`f64 + u8[4]` is invalid")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
