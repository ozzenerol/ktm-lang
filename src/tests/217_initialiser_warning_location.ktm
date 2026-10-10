#include "testhelper.ktm"

/* A class initialiser item of the wrong type for its field was reported
 * at the token after it (the `}` or `,`) instead of at the item. It is
 * now reported at the item, underlining all of it: globals, locals,
 * brace-elided nested classes, items on the next line. */

#define IWL_SRC "./ktm-217-iwl.ktm"
#define IWL_OUT "./ktm-217-iwl.out"

/* Compile `source` and return what ktm printed */
U8 *Diagnose(U8 *source)
{
  System("rm -f " IWL_SRC " " IWL_OUT);
  FileWrite(IWL_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " IWL_SRC " -o /dev/null > " IWL_OUT " 2>&1");
  U8 *out = FileRead(IWL_OUT);
  System("rm -f " IWL_SRC " " IWL_OUT);
  return out;
}

/* ktm's output has every one of `want` */
Bool Has(U8 *source, U8 *want1, U8 *want2, U8 *want3 = NULL)
{
  U8 *out = Diagnose(source);
  Bool ok = out && strstr(out, want1) && strstr(out, want2) &&
            (!want3 || strstr(out, want3));
  if (!ok) "got: '%s'\n", out;
  Free(out);
  return ok;
}

#define CLS "class Q { I64 a; I64 *p; };\nclass R { I64 a; Q q; I64 b; };\n" \
            "F64 f;\n"
#define MSG "expected 'I64 *' got 'F64"

I32 Main()
{
  "Test - initialiser warning location: ";
  I64 tests = 0;
  I64 correct = 0;

  tests++;
  if (Has(CLS "Q qq = {1, f};\n", MSG, "217-iwl.ktm:4:12\n",
          "|               ^\n"))
    correct++;
  else "Failed - global, last item\n";

  tests++;
  if (Has(CLS "R r = {1, {2, f}, 3};\n", MSG, "217-iwl.ktm:4:15\n"))
    correct++;
  else "Failed - nested braced class\n";

  tests++;
  if (Has(CLS "R r = {1, 2, f, 3};\n", MSG, "217-iwl.ktm:4:14\n"))
    correct++;
  else "Failed - brace-elided nested class\n";

  tests++;
  if (Has(CLS "Q qq = {1, f * 2};\n", MSG, "217-iwl.ktm:4:12\n",
          "|               ^^^^^\n"))
    correct++;
  else "Failed - expression item\n";

  tests++;
  if (Has(CLS "U0 Main() {\n  Q lq = {1,\n          f};\n}\n", MSG,
          "217-iwl.ktm:6:11\n"))
    correct++;
  else "Failed - local, item on the next line\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
