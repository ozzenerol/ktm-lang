#include "testhelper.ktm"

/* Bool converts like C's _Bool (any non-zero value is 1), but the
 * diagnostics hadn't caught up: a pointer converted to Bool warned
 * "Incompatible types 'U8 *' is not assignable to type 'I8'", where C
 * doesn't warn, and every message spelled Bool as 'I8'. Pointer and
 * function to Bool is now silent (initialiser, assignment, class field,
 * argument, return), pointer to a narrower integer still warns, and
 * messages and typeof say Bool. */

#define DIAG_SRC "./ktm-391-bool.ktm"
#define DIAG_OUT "./ktm-391-bool.out"

/* Compile `source` and return what ktm printed */
U8 *Diagnose(U8 *source)
{
  System("rm -f " DIAG_SRC " " DIAG_OUT);
  FileWrite(DIAG_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " DIAG_SRC " -o /dev/null > " DIAG_OUT " 2>&1");
  U8 *out = FileRead(DIAG_OUT);
  System("rm -f " DIAG_SRC " " DIAG_OUT);
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

/* ktm prints `warnings` warnings, no error, and each of want1/want2 */
Bool Prints(U8 *source, I64 warnings, U8 *want1, U8 *want2=NULL)
{
  U8 *out = Diagnose(source);
  Bool ok = out && Count(out, "warning:") == warnings &&
            Count(out, "error:") == 0 &&
            (!want1 || strstr(out, want1)) && (!want2 || strstr(out, want2));
  if (!ok) "\n%s\n", out;
  Free(out);
  return ok;
}

I64 g_called;
U0 Fn() { g_called++; }
Bool AsBool(Bool b) { return b; }
Bool PtrToBool(U8 *p) { return p; }

I32 Main()
{
  "Test - Bool diagnostics: ";
  I64 correct = 0, tests = 0;

  /* pointer, array and function to Bool: no warning anywhere */
  tests++;
  if (Prints(
"class C { Bool f; };
U0 F() {}
U0 Take(Bool b) {}
Bool Ret(U8 *p) { return p; }
Bool RetFn() { return &F; }
U0 Main()
{
  U8 *p = \"x\", buf[4];
  Bool a = p, b = buf, f = &F;
  C c;
  a = p;
  c.f = p;
  Take(p);
  Take(&F);
}
", 0, NULL)) correct++;

  /* other pointer to integer conversions still warn */
  tests++;
  if (Prints(
"I32 Ret(U8 *p) { return p; }
U0 Take(U8 b) {}
U0 Main()
{
  U8 *p = \"x\";
  I32 i = p;
  U8 u = p;
  I8 s = p;
  Take(p);
}
", 5, "'U8 *' is not assignable to type 'I32'",
      "expected 'U8' got 'U8 *'")) correct++;

  /* messages spell Bool as Bool */
  tests++;
  if (Prints(
"class C { I64 a; };
U0 Take(Bool b) {}
Bool Ret() { C c; return c; }
U0 Main()
{
  C c;
  Take(c);
}
", 2, "Bool Ret(U0); unexpected return value 'c' of type 'C' expected 'Bool'",
      "expected 'Bool' got 'C' in function 'Take'")) correct++;

  tests++;
  U8 *out = Diagnose(
"class C { I64 a; };
U0 Main()
{
  C c;
  Bool b = c;
}
");
  if (out && strstr(out, "`Bool = C` is invalid") &&
      strstr(out, "applied to a Bool type")) correct++;
  else "\n%s\n", out;
  Free(out);

  /* typeof names the type the same way */
  Bool bb;
  tests++;
  if (!StrCmp(typeof(bb), "Bool") && !StrCmp(typeof(&bb), "Bool *")) correct++;
  else "typeof %s / %s ", typeof(bb), typeof(&bb);

  /* and the conversions give 0 or 1 */
  U8 *p = "x", *np = NULL;
  Bool t = p, n = np, f = &Fn;
  tests++;
  if (t == 1 && n == 0 && f == 1 && AsBool(p) == 1 && AsBool(np) == 0 &&
      PtrToBool(p) == 1 && PtrToBool(NULL) == 0) correct++;
  else "values %d %d %d ", t, n, f;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
