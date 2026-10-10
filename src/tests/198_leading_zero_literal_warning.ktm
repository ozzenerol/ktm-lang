#include "testhelper.ktm"

/* ktm has no octal literals: `0644` is the decimal number 644 (C
 * reads octal 420). That meaning stays, but it was silent; ktm now
 * warns at the literal when C would read a different number, and the
 * program still compiles and runs with the decimal value. */

#define LZ_FILE "__198_leading_zero.ktm"
#define LZ_OUT  "__198_leading_zero.out"
/* `timeout` is coreutils; without it the command just runs */
#define LZ_KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U0 CleanupLeadingZeroTest(U0)
{
  Rm(LZ_FILE);
  Rm(LZ_OUT);
  Rm("./a.out");
}

I64 CountWarnings(U8 *out)
{
  I64 n = 0;
  U8 *p = out;
  while ((p = strstr(p, "leading zero: "))) {
    n++;
    p++;
  }
  return n;
}

/* Run `source` under the JIT (with `flags`). It must exit with
 * `status`, print `nwarn` leading-zero diagnostics and contain each of
 * `want1` / `want2` (NULL to skip). */
Bool RunLeadingZero(U8 *flags, U8 *source, I64 status, I64 nwarn,
                    U8 *want1, U8 *want2)
{
  U8 *cmd, *out;
  I64 res;
  Bool ok = TRUE;

  CleanupLeadingZeroTest;
  if (!FileWrite(LZ_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  cmd = MStrPrint(LZ_KTM "%s -jit " LZ_FILE " > " LZ_OUT " 2>&1", flags);
  res = System(cmd);
  Free(cmd);

  out = FileRead(LZ_OUT);
  if (!out) {
    CleanupLeadingZeroTest;
    return FALSE;
  }
  if (res != status) ok = FALSE;
  if (CountWarnings(out) != nwarn) ok = FALSE;
  if (want1 && !strstr(out, want1)) ok = FALSE;
  if (want2 && !strstr(out, want2)) ok = FALSE;
  if (!ok) "exit status %d\n%s\n", res, out;
  Free(out);
  CleanupLeadingZeroTest;
  return ok;
}

U0 Main()
{
  "Test - leading zero literal warning: ";
  I64 correct = 0, tests = 0;

  /* The warning, at the literal, and the value is still decimal */
  tests++;
  if (RunLeadingZero("",
"U0 Main() {
  I64 mode = 0644;
  \"value=%d\\n\", mode;
}
", 0, 1,
      "warning: leading zero: 0644 is the decimal number 644 (ktm has no octal literals); use 0x1A4 for octal 0644",
      "value=644")) correct++;

  /* Pointed at the literal: line 2, column 14 */
  tests++;
  if (RunLeadingZero("",
"U0 Main() {
  I64 mode = 0644;
}
", 0, 1, ":2:14", NULL)) correct++;

  /* No warning where C and ktm agree or it is not an integer
   * literal with a leading zero */
  tests++;
  if (RunLeadingZero("",
"U0 Main() {
  I64 a = 0, b = 0x1A4, c = 0b101, d = 07, e = 08, f = 00, g = 0X10;
  F64 h = 0.5, i = 00.5, j = 0644.0, k = 0e3, m = 0644e1;
  \"%d %d %d %d %d %d %d %.1f %.1f %.1f %.1f %.1f\\n\",
    a, b, c, d, e, f, g, h, i, j, k, m;
}
", 0, 0, "0 420 5 7 8 0 16 0.5 0.5 644.0 0.0 6440.0", NULL)) correct++;

  /* Each literal is reported once, a macro at its definition; the
   * U suffix is allowed */
  tests++;
  if (RunLeadingZero("",
"#define PERM 0755
U0 Main() {
  I64 a = 010U, b = 0777;
  \"%d %d %d %d\\n\", PERM, PERM, a, b;
}
", 0, 3, "use 0x8 for octal 010", "755 755 10 777")) correct++;

  /* Nothing from a skipped #if group */
  tests++;
  if (RunLeadingZero("",
"U0 Main() {
#if 0
  I64 a = 0644;
#endif
  \"ok\\n\";
}
", 0, 0, "ok", NULL)) correct++;

  /* An error with -Werror */
  tests++;
  if (RunLeadingZero("-Werror",
"U0 Main() {
  I64 mode = 0644;
}
", 1, 1, "rror: leading zero: 0644", NULL)) correct++;

  PrintResult(correct, tests);
  "====\n";
}
