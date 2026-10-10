#include "testhelper.ktm"

/* `y = p` with an integer `y` and a class `p` printed a type warning
 * and then the error for the same statement. Only the error is printed
 * now; an assignment the operator allows still gets the warning. */

#define DUP_SRC "./ktm-218-dup.ktm"
#define DUP_OUT "./ktm-218-dup.out"

/* Compile `source` and return what ktm printed */
U8 *Diagnose(U8 *source)
{
  System("rm -f " DUP_SRC " " DUP_OUT);
  FileWrite(DUP_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " DUP_SRC " -o /dev/null > " DUP_OUT " 2>&1");
  U8 *out = FileRead(DUP_OUT);
  System("rm -f " DUP_SRC " " DUP_OUT);
  return out;
}

I64 Count(U8 *s, U8 *what)
{
  I64 n = 0;
  while (s && (s = strstr(s, what))) {
    n++;
    s++;
  }
  return n;
}

/* ktm prints `errors` errors, `warnings` warnings, and `want` */
Bool Prints(U8 *source, I64 errors, I64 warnings, U8 *want)
{
  U8 *out = Diagnose(source);
  Bool ok = out && Count(out, "error:") == errors &&
            Count(out, "warning:") == warnings && strstr(out, want);
  if (!ok) "got: '%s'\n", out;
  Free(out);
  return ok;
}

#define CLS "class P { I64 x, y; };\n"

I32 Main()
{
  "Test - one diagnostic for an invalid assignment: ";
  I64 tests = 0;
  I64 correct = 0;

  tests++;
  if (Prints(CLS "U0 Main() {\n  I64 y = 1; P p; y = p;\n}\n", 1, 0,
             "218-dup.ktm:3:21\n"))
    correct++;
  else "Failed - y = p\n";

  tests++;
  if (Prints(CLS "U0 Main() {\n  I64 y = 1; P p; p = y;\n}\n", 1, 0,
             "`P = I64` is invalid"))
    correct++;
  else "Failed - p = y\n";

  tests++;
  if (Prints(CLS "U0 Main() {\n  I64 y = 1; P p; y += p;\n}\n", 1, 0,
             "`I64 += P` is invalid"))
    correct++;
  else "Failed - y += p\n";

  tests++;
  if (Prints(CLS "P gp;\nI64 gz = gp;\n", 1, 0, "`I64 = P` is invalid"))
    correct++;
  else "Failed - global initialiser\n";

  /* A valid operation with a doubtful type is still warned about */
  tests++;
  if (Prints("U0 Main() {\n  I64 *q; F64 f;\n  q = f;\n}\n", 0, 1,
             "Incompatible types 'F64' is not assignable to type 'I64 *'"))
    correct++;
  else "Failed - warning kept\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
