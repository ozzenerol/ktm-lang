#include "testhelper.ktm"

/* ktm sources are .ktm and headers .ktmh. The old .HC/.HH names (and any
 * other extension) are a clean break: as the input file they stop the
 * compiler with a message saying so, and an #include of such a file is an
 * error. .ktm/.ktmh includes and an include without an extension work. */

#define KTM "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U0 Put(U8 *name, U8 *text)
{
  FileWrite(name, text, StrLen(text));
}

/* The output of the shell command, or NULL on a failed exit. */
Bool Has(U8 *out, U8 *what)
{
  return out && strstr(out, what) != NULL;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  U8 *out;
  "Test - .ktm / .ktmh only: ";

  Put("__399_ok.ktmh", "I64 Seven() { return 7; }\n");
  Put("__399_noext", "I64 Eight() { return 8; }\n");
  Put("__399_main.ktm", "#include \"__399_ok.ktmh\"\n#include \"__399_noext\"\nU0 Main() { \"%d\\n\", Seven + Eight; }\n");
  Put("__399_old.HC", "U0 Main() { \"old\\n\"; }\n");
  Put("__399_inc_old.ktm", "#include \"__399_old.HC\"\nU0 Main() {}\n");
  Put("__399_inc_c.ktm", "#include \"__399_x.c\"\nU0 Main() {}\n");
  Put("__399_x.c", "int x;\n");

  tests++;
  out = Shlurp(KTM "-jit __399_main.ktm 2>&1");
  if (out && !StrCmp(out, "15")) correct++;
  else "\n  .ktm with .ktmh and extensionless includes: [%s]\n", out;

  tests++;
  out = Shlurp(KTM "__399_old.HC -o __399_old 2>&1; echo \"rc=$?\"");
  if (Has(out, "`__399_old.HC`: ktm sources use .ktm (headers .ktmh)") && Has(out, "rc=1")) correct++;
  else "\n  .HC input is rejected: [%s]\n", out;

  tests++;
  out = Shlurp(KTM "-jit __399_old.HC 2>&1; echo \"rc=$?\"");
  if (Has(out, "ktm sources use .ktm") && Has(out, "rc=1")) correct++;
  else "\n  .HC input is rejected under -jit: [%s]\n", out;

  tests++;
  out = Shlurp(KTM "-jit __399_inc_old.ktm 2>&1");
  if (Has(out, "#include: `") && Has(out, "__399_old.HC`: ktm sources use .ktm (headers .ktmh)")) correct++;
  else "\n  #include of a .HC file is an error: [%s]\n", out;

  tests++;
  out = Shlurp(KTM "-jit __399_inc_c.ktm 2>&1");
  if (Has(out, "__399_x.c`: ktm sources use .ktm")) correct++;
  else "\n  #include of a .c file is an error: [%s]\n", out;

  tests++;   /* after the input, a .HC word is just an argument for the program */
  Put("__399_args.ktm", "I32 Main(I32 argc, U8 **argv) { \"%s\\n\", argv[1]; return 0; }\n");
  out = Shlurp(KTM "-jit __399_args.ktm notes.HC 2>&1");
  if (out && !StrCmp(out, "notes.HC")) correct++;
  else "\n  a .HC program argument after the input: [%s]\n", out;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
