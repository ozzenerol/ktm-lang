#include "testhelper.ktm"

/* `return` in top-level code (`if (x) return 0;` outside any function)
 * crashed ktm, AOT and JIT: parseReturnStatement used the current
 * function's name and return type, which are NULL there. It is an error
 * at the `return` now. Also checks that a file ending inside an unclosed
 * block, (, [, string, char, comment or class body gives an error with
 * its location rather than a crash. */

#define RO_SRC "371-src.ktm"
#define RO_OUT "371-src.out"

/* Compile `source` (JIT when jit) and return what ktm printed, or NULL
 * when ktm did not exit with 1 (a crash or a success). */
U8 *Diagnose(U8 *source, Bool jit)
{
  U8 *cmd, *out = NULL, *mode = "-S -o /dev/null";
  I64 res;
  if (jit) mode = "-jit";
  System("rm -f " RO_SRC " " RO_OUT);
  FileWrite(RO_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  cmd = MStrPrint("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                  "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                  "%s " RO_SRC " > " RO_OUT " 2>&1",
                  mode);
  res = System(cmd);
  if (res == 1) out = FileRead(RO_OUT);
  else "ktm exited with %d\n", res;
  System("rm -f " RO_SRC " " RO_OUT);
  Free(cmd);
  return out;
}

/* AOT and JIT both fail cleanly and print `msg` and `where` */
Bool Prints(U8 *source, U8 *msg, U8 *where)
{
  Bool ok = TRUE;
  for (I64 jit = 0; jit < 2; ++jit) {
    U8 *out = Diagnose(source, jit);
    if (!out || !strstr(out, msg) || !strstr(out, where)) {
      if (jit) "JIT: ";
      else     "AOT: ";
      "got: '%s'\n", out;
      ok = FALSE;
    }
    Free(out);
  }
  return ok;
}

I32 Main()
{
  "Test - return outside a function: ";
  I64 tests = 0;
  I64 correct = 0;

  tests++;
  if (Prints("if (1) {\n  return 0;", "`return` outside a function",
             RO_SRC ":2:3\n")) correct++;
  else "Failed - return in an unclosed top-level block\n";

  tests++;
  if (Prints("I64 x = 1;\nif (x) return 0;\n", "`return` outside a function",
             RO_SRC ":2:8\n")) correct++;
  else "Failed - return in a top-level if\n";

  tests++;
  if (Prints("U0 F() { return; }\nwhile (1) {\n  return;\n}\n",
             "`return` outside a function", RO_SRC ":3:3\n")) correct++;
  else "Failed - return in a top-level while after a function\n";

  /* end of file inside an unclosed construct */
  tests++;
  if (Prints("I32 Main()\n{\n  if (1) {\n    return 0;\n",
             "Unexpected end of input, expected `}`", RO_SRC ":4")) correct++;
  else "Failed - unclosed {\n";

  tests++;
  if (Prints("I64 x = (1 + 2", "Unexpected end of input, expected `)`",
             RO_SRC ":1")) correct++;
  else "Failed - unclosed (\n";

  tests++;
  if (Prints("I64 x[4];\nI32 Main()\n{\n  return x[1",
             "Unexpected end of input, expected `]`", RO_SRC ":4")) correct++;
  else "Failed - unclosed [\n";

  tests++;
  if (Prints("U8 *s = \"abc", "Unterminated string", RO_SRC ":1:9\n")) correct++;
  else "Failed - unclosed string\n";

  tests++;
  if (Prints("I32 Main()\n{\n  U8 c = 'a", "Unterminated char const",
             RO_SRC ":3:10\n")) correct++;
  else "Failed - unclosed char\n";

  tests++;
  if (Prints("I32 Main()\n{\n  /* comment\n  return 0;\n",
             "unterminated comment", RO_SRC ":3:3\n")) correct++;
  else "Failed - unclosed comment\n";

  tests++;
  if (Prints("class Foo\n{\n  I64 a;\n", "Unexpected end of input in class/union body",
             RO_SRC ":3")) correct++;
  else "Failed - unclosed class body\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
