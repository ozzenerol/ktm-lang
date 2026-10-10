#include "testhelper.ktm"

/* `ktm -jit` on a file with no Main and no code at file scope looked
 * up a `main` that was never made and exited 255 with nothing printed.
 * It now says there is nothing to run. Files with statements at file
 * scope get a synthetic main and must keep running. */

#define NM_FILE "__164_jit_no_main.ktm"
#define NM_OUT  "__164_jit_no_main.out"

U0 CleanupNoMainTest(U0)
{
  Rm(NM_FILE);
  Rm(NM_OUT);
}

/* Run `ktm -jit` on `source`; true if the exit status is zero exactly
 * when `want_ok` is, and the output contains `expected` (or, with
 * `expected` "", is empty) */
Bool JitRuns(U8 *source, Bool want_ok, U8 *expected)
{
  U8 *out;
  I64 res;
  Bool ok = TRUE;

  CleanupNoMainTest;
  if (!FileWrite(NM_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  res = System("T=; command -v timeout >/dev/null 2>&1 && T='timeout 30'; "
               "$T ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               "-jit " NM_FILE " > " NM_OUT " 2>&1");
  out = FileRead(NM_OUT);
  if (!out) {
    CleanupNoMainTest;
    return FALSE;
  }
  if ((res == 0) != want_ok) ok = FALSE;
  if (*expected && !strstr(out, expected)) ok = FALSE;
  if (!*expected && StrLen(out) != 0) ok = FALSE;
  if (!ok) "\nstatus %d, want ok %d, output:\n%s\n", res, want_ok, out;
  Free(out);
  CleanupNoMainTest;
  return ok;
}

#define NO_MAIN_MSG "ktm: __164_jit_no_main.ktm: nothing to run: no Main function and no code at file scope"

I32 Main()
{
  I64 correct = 0, tests = 0;

  "Test - ktm -jit with no Main: ";

  /* nothing to run: reported */
  tests++; if (JitRuns("I64 Foo() { return 1; }\n", FALSE, NO_MAIN_MSG)) correct++;
  tests++; if (JitRuns("", FALSE, NO_MAIN_MSG)) correct++;
  tests++; if (JitRuns("// only a comment\n", FALSE, NO_MAIN_MSG)) correct++;
  tests++; if (JitRuns("class C { I64 a; };\nI64 g;\n", FALSE, NO_MAIN_MSG)) correct++;
  /* code at file scope: runs as before */
  tests++; if (JitRuns("\"top\\n\";\n", TRUE, "top\n")) correct++;
  tests++; if (JitRuns("I64 Two() { return 2; }\n\"%d\\n\", Two;\n", TRUE, "2\n")) correct++;
  /* a constant initialiser is static data, like `I64 g;` (no code); one
   * that calls a function is code at file scope */
  tests++; if (JitRuns("I64 x = 3;\nI64 Foo() { return x; }\n", FALSE, NO_MAIN_MSG)) correct++;
  tests++; if (JitRuns("I64 x = StrLen(\"ab\");\nI64 Foo() { return x; }\n", TRUE, "")) correct++;
  /* a Main: runs as before */
  tests++; if (JitRuns("U0 Main() { \"main\\n\"; }\n", TRUE, "main\n")) correct++;
  tests++; if (JitRuns("I32 Main() { return 3; }\n", FALSE, "")) correct++;

  CleanupNoMainTest;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
