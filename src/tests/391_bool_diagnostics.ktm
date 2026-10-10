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
  "Test - bool diagnostics: ";
  I64 correct = 0, tests = 0;
  U8 *out;

  /* pointer, array and function to Bool: no warning anywhere */
  tests++;
  if (Prints(
"class C { bool f; };
void F() {}
void Take(bool b) {}
bool Ret(u8 *p) { return p; }
bool RetFn() { return &F; }
void Main()
{
  u8 *p = \"x\", buf[4];
  bool a = p, b = buf, f = &F;
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
"i32 Ret(u8 *p) { return p; }
void Take(u8 b) {}
void Main()
{
  u8 *p = \"x\";
  i32 i = p;
  u8 u = p;
  i8 s = p;
  Take(p);
}
", 5, "'u8 *' is not assignable to type 'i32'",
      "expected 'u8' got 'u8 *'")) correct++;

  /* messages spell Bool as Bool (a class passed for a bool is an
   * error since argument types are checked like C) */
  tests++;
  out = Diagnose(
"class C { i64 a; };
void Take(bool b) {}
bool Ret() { C c; return c; }
void Main()
{
  C c;
  Take(c);
}
");
  if (out && Count(out, "warning:") == 1 && Count(out, "error:") == 1 &&
      strstr(out, "bool Ret(void); unexpected return value 'c' of type 'C' expected 'bool'") &&
      strstr(out, "error: Incompatible function argument, expected 'bool' got 'C' in function 'Take'")) {
    correct++;
  } else {
    "\n%s\n", out;
  }
  Free(out);

  tests++;
  out = Diagnose(
"class C { i64 a; };
void Main()
{
  C c;
  bool b = c;
}
");
  if (out && strstr(out, "`bool = C` is invalid") &&
      strstr(out, "applied to a bool type")) correct++;
  else "\n%s\n", out;
  Free(out);

  /* typeof names the type the same way */
  Bool bb;
  tests++;
  if (!StrCmp(typeof(bb), "bool") && !StrCmp(typeof(&bb), "bool *")) correct++;
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
