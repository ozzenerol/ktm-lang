#include "testhelper.ktm"

/* `fallthrough;` (ktm syntax v0.2) says a case goes on into the next
 * one. Cases fall through anyway today, so it does nothing; it is an
 * error outside a switch and as the last statement of the last case,
 * where there is no next case. `fallthrough` stays usable as a name. */

#define AT_SRC "./ktm-436-fallthrough.ktm"
#define AT_OUT "./ktm-436-fallthrough.out"

U8 *Diagnose(U8 *source)
{
  System("rm -f " AT_SRC " " AT_OUT);
  FileWrite(AT_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " AT_SRC " -o /dev/null > " AT_OUT " 2>&1");
  U8 *out = FileRead(AT_OUT);
  System("rm -f " AT_SRC " " AT_OUT);
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

/* ktm prints `errors` errors, no warnings, and want (if any) */
Bool Prints(U8 *body, I64 errors, U8 *want=NULL)
{
  U8 *src = MStrPrint("%s\ni32 main() { return 0; }\n", body);
  U8 *out = Diagnose(src);
  Bool ok = out && Count(out, "error:") == errors &&
            Count(out, "warning:") == 0 && (!want || strstr(out, want));
  if (!ok) "\n%s\n----\n%s\n", body, out;
  Free(out);
  Free(src);
  return ok;
}

#define OUTSIDE "`fallthrough` can only be used in a case of a switch"
#define LAST "`fallthrough` in the last case of a switch: there is no next case"

i64 F(i64 x)
{
  i64 r = 0;
  switch (x) {
    case 1: r += 1; fallthrough;
    case 2: r += 10; break;
    case 3: { r += 100; fallthrough; }
    case 4: r += 1000; fallthrough;
    default: r += 5;
  }
  return r;
}

/* a switch inside a case: each has its own last case */
i64 H(i64 x)
{
  switch (x) {
    case 1:
      switch (x) {
        case 1: fallthrough;
        case 2: x += 2;
      }
      fallthrough;
    case 2: x += 10;
  }
  return x;
}

i64 G(i64 fallthrough) { return fallthrough + 1; }

I32 Main()
{
  "Test - fallthrough: ";
  I64 correct = 0, tests = 0;

  tests++; if (F(1) == 11 && F(2) == 10 && F(3) == 1105 && F(4) == 1005 && F(9) == 5) correct++;
  tests++; if (H(1) == 13 && H(2) == 12) correct++;
  tests++; if (G(1) == 2) correct++;

  /* a variable of that name keeps it a name */
  tests++;
  if (Prints("i64 fallthrough = 3;\nvoid f(i64 x) { switch (x) { case 1: fallthrough; case 2: x++; } }", 0))
    correct++;

  tests++; if (Prints("void f() { fallthrough; }", 1, OUTSIDE)) correct++;
  tests++; if (Prints("fallthrough;", 1, OUTSIDE)) correct++;
  tests++;
  if (Prints("void f(i64 x) { switch (x) { case 1: x++; fallthrough; } }", 1, LAST))
    correct++;
  tests++;
  if (Prints("void f(i64 x) { switch (x) { case 1: break; default: fallthrough; } }", 1, LAST))
    correct++;
  tests++;
  if (Prints("void f(i64 x) { switch (x) { case 1: fallthrough; case 2: switch (x) { case 3: fallthrough; } } }",
             1, LAST))
    correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
