#include "testhelper.ktm"

/* ktm syntax v0.2: the entry point can be `main` in lowercase: `i32 main()`,
 * `i32 main(i32 argc, u8 **argv)` or `void main()`, with the same behaviour
 * as Main (its return value is the exit code, the arguments arrive, a
 * void main exits with 0, file-scope initialisers run first). A program
 * can't define both main and Main. Each program runs compiled and in the
 * JIT. */

#define KTM "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U0 Put(U8 *name, U8 *text)
{
  FileWrite(name, text, StrLen(text));
}

/* Output and exit code of the program in file, run with args, both ways:
 * TRUE when both print `want` and exit with `code`. */
Bool Runs(U8 *file, U8 *want, I64 code)
{
  U8 *jit = Shlurp(KTM "-jit %s one two; echo \"rc=$?\"", file);
  U8 *aot = Shlurp(KTM "%s -o __405_bin && ./__405_bin one two; echo \"rc=$?\"", file);
  U8 *expect = MStrPrint("%s\nrc=%d", want, code);
  Bool ok = jit && aot && !StrCmp(jit, expect) && !StrCmp(aot, expect);
  if (!ok) "\n  %s: want [%s]\n    jit [%s]\n    aot [%s]\n", file, expect, jit, aot;
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - lowercase main: ";

  tests++;
  Put("__405_a.ktm", "i32 main()\n{\n  printf(\"plain\\n\");\n  return 3;\n}\n");
  if (Runs("__405_a.ktm", "plain", 3)) correct++;

  tests++;   /* argc/argv: the JIT's argv[0] is the source, AOT's the binary */
  Put("__405_b.ktm", "i32 main(i32 argc, u8 **argv)\n{\n  printf(\"%d %s %s\\n\", argc, argv[1], argv[2]);\n  return argc;\n}\n");
  if (Runs("__405_b.ktm", "3 one two", 3)) correct++;

  tests++;
  Put("__405_c.ktm", "void main()\n{\n  printf(\"void\\n\");\n}\n");
  if (Runs("__405_c.ktm", "void", 0)) correct++;

  tests++;   /* file-scope initialisers run first, then main with its arguments */
  Put("__405_d.ktm", "i64 g = 4;\ng = g * 10;\ni32 main(i32 argc, u8 **argv)\n{\n  printf(\"%d %s\\n\", g, argv[2]);\n  return 7;\n}\n");
  if (Runs("__405_d.ktm", "40 two", 7)) correct++;

  tests++;   /* a prototype first, and main called by that name from elsewhere */
  Put("__405_e.ktm", "i32 main();\ni64 calls = 0;\nvoid Again() { if (calls < 2) main(); }\ni32 main()\n{\n  calls++;\n  Again();\n  if (calls == 2) {\n    printf(\"calls %d\\n\", calls);\n    calls = 99;\n  }\n  return 0;\n}\n");
  if (Runs("__405_e.ktm", "calls 2", 0)) correct++;

  tests++;   /* one entry point only */
  Put("__405_f.ktm", "U0 Main() {}\nvoid main() {}\n");
  U8 *err = Shlurp(KTM "-jit __405_f.ktm 2>&1");
  if (err && strstr(err, "both `main` and `Main` are defined")) correct++;
  else "\n  main and Main: [%s]\n", err;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
