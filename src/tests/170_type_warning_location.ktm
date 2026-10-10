#include "testhelper.ktm"

/* "Incompatible types" warnings for assignments, compound assignments
 * and declarations were placed by rewinding to the nearest `=`, so with
 * two statements on a line (or a long right hand side) the warning for
 * one landed on the other. They are reported at the statement's own
 * operator. */

#define TW_TEST_FILE "__170_type_warning_loc.ktm"
#define TW_TEST_OUT  "__170_type_warning_loc.out"

U0 CleanupTwTest()
{
  Rm(TW_TEST_FILE);
  Rm(TW_TEST_OUT);
  Rm("./a.out");
}

/* Compiles `src`; TRUE if the output has `what` at `where` (file:line:col)
 * and nothing at `not_where` (if given). */
Bool DiagAt(U8 *src, U8 *what, U8 *where, U8 *not_where = NULL)
{
  U8 *out, *at;
  Bool ok = FALSE;
  CleanupTwTest;
  if (!FileWrite(TW_TEST_FILE, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " TW_TEST_FILE " > " TW_TEST_OUT " 2>&1");
  out = FileRead(TW_TEST_OUT);
  if (out) {
    at = strstr(out, what);
    /* The location line follows the message */
    ok = at && strstr(at, where) && strstr(at, where) - at < 200;
    if (ok && not_where && strstr(out, not_where)) ok = FALSE;
    if (!ok) "%s\n", out;
    Free(out);
  }
  CleanupTwTest;
  return ok;
}

#define DECLS "U0 Main()\n{\n  I64 y = 1, *q = NULL;\n  F64 f = 1;\n  I64 arr[2];\n"
#define WARN "Incompatible types 'I64 *' is not assignable to type 'F64'"

I32 Main()
{
  "Test - type warning location: ";
  I64 correct = 0, tests = 0;

  /* Second statement on a line: its own `=`, not the first one's */
  tests++;
  if (DiagAt(DECLS "  y = y + 1; f = q;\n}\n", WARN, ":6:16", ":6:5")) correct++;
  /* Declaration after an assignment */
  tests++;
  if (DiagAt(DECLS "  y = 2; F64 g = q;\n}\n", WARN, ":6:16", ":6:5")) correct++;
  /* Right hand side over several lines */
  tests++;
  if (DiagAt(DECLS "  y = 3;\n  f =\n      q;\n}\n", WARN, ":7:5")) correct++;
  /* Compound assignment after a plain one: the error is at its own `+=` */
  tests++;
  if (DiagAt(DECLS "  f = 2; f += q;\n}\n", "The `+=` operator", ":6:12", ":6:5")) correct++;
  /* An array right hand side */
  tests++;
  if (DiagAt(DECLS "  y = 4; f = arr;\n}\n", "Incompatible types 'I64[", ":6:12")) correct++;
  /* Top level statements */
  tests++;
  if (DiagAt("I64 *gq = NULL;\nF64 gf;\nI64 gy;\ngy = 1; gf = gq;\nU0 Main() { }\n",
             WARN, ":4:12", ":4:4")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
