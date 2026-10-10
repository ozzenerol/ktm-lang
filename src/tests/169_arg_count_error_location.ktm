#include "testhelper.ktm"

/* A wrong argument count was underlined from the first `(` to the first
 * `)` of the line, so `U0 Main() { I64 x = One(1); I64 y = Two(1); }`
 * underlined `Main()`. It is reported at the call itself, from the name
 * to the closing `)`. */

#define AC_TEST_FILE "__169_arg_count_loc.ktm"
#define AC_TEST_OUT  "__169_arg_count_loc.out"

U0 CleanupAcTest()
{
  Rm(AC_TEST_FILE);
  Rm(AC_TEST_OUT);
  Rm("./a.out");
}

/* Compiles `body` (line 6 of the file) and checks the output has both
 * `where` (file:line:col) and `underline` (the caret line's carets). */
Bool ErrorAt(U8 *body, U8 *where, U8 *underline)
{
  U8 *src = MStrPrint("I64 One(I64 a) { return a; }\n"
                      "I64 Two(I64 a, I64 b) { return a + b; }\n"
                      "I64 Va(I64 a, ...) { return a; }\n"
                      "class C { I64 (*f)(I64 a); };\n"
                      "C c;\n"
                      "U0 Main() { %s }\n", body);
  U8 *out;
  Bool ok = FALSE;
  CleanupAcTest;
  if (FileWrite(AC_TEST_FILE, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC)) {
    if (System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " AC_TEST_FILE " > " AC_TEST_OUT " 2>&1") != 0) {
      out = FileRead(AC_TEST_OUT);
      if (out) {
        ok = strstr(out, "Unexpected number of arguments") && strstr(out, where) &&
             strstr(out, underline);
        if (!ok) "%s\n", out;
        Free(out);
      }
    }
  }
  Free(src);
  CleanupAcTest;
  return ok;
}

I32 Main()
{
  "Test - argument count error location: ";
  I64 correct = 0, tests = 0;

  /* `U0 Main() { ` is 12 columns */
  tests++;
  if (ErrorAt("I64 x = One(1); I64 y = Two(1);", ":6:37", "|                                        ^^^^^^\n")) correct++;
  tests++;
  if (ErrorAt("I64 y = Two (1, 2, 3);", ":6:21", "|                        ^^^^^^^^^^^^^\n")) correct++;
  tests++;
  if (ErrorAt("I64 (*fp)(I64 a, I64 b) = &Two; I64 y = fp(1);", ":6:53", "^^^^^\n")) correct++;
  tests++;
  if (ErrorAt("c.f = &One; I64 y = c.f();", ":6:35", "|                                      ^^^\n")) correct++;
  tests++;
  if (ErrorAt("I64 y = 1 + Two;", ":6:25", "|                            ^^^\n")) correct++;
  tests++;
  if (ErrorAt("I64 x = One(1); I64 y = Va();", ":6:37", "^^^^\n")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
