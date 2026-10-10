#include "testhelper.ktm"

/* A brace initialiser for a class that was only forward declared
 * (`class Foo;`) is reported as an error. ktm used to read the missing
 * field map and segfault (exit 139, no diagnostic). */

#define FWD_INIT_FILE "__125_forward_class_init.ktm"
#define FWD_INIT_OUT  "__125_forward_class_init.out"

#define MSG_INCOMPLETE "Cannot use an initialiser list, Foo is incomplete"

/* Declared here, defined below: an initialiser after the definition
 * works as usual. */
class Fwd125;
Fwd125 *fwd_125_ptr = NULL;
class Fwd125
{
  I64 a;
  I64 b;
};
Fwd125 fwd_125_global = {3, 4};

U0 CleanupFwdInitTest(U0)
{
  Rm(FWD_INIT_FILE);
  Rm(FWD_INIT_OUT);
  Rm("./a.out");
}

I64 CountErrors(U8 *out)
{
  I64 n = 0;
  U8 *p = out;
  while ((p = strstr(p, "error: "))) {
    n++;
    p += 7;
  }
  return n;
}

/* Compile `source`: ktm must exit with status 1 (not crash) and print
 * exactly `n_expected` errors, `m1`/`m2` (when not NULL) among them. */
Bool CompileFailsExactly(U8 *source, I64 n_expected, U8 *m1, U8 *m2)
{
  U8 *out;
  I64 res;
  Bool ok;

  CleanupFwdInitTest;

  if (!FileWrite(FWD_INIT_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " FWD_INIT_FILE " > " FWD_INIT_OUT " 2>&1");

  out = FileRead(FWD_INIT_OUT);
  if (!out) {
    CleanupFwdInitTest;
    return FALSE;
  }

  /* System gives the exit code: 1 for a compile error; a segfault
   * would be 139 (from the shell) or -1 (killed by a signal). */
  ok = res == 1;
  if (CountErrors(out) != n_expected) ok = FALSE;
  if (m1 && !strstr(out, m1)) ok = FALSE;
  if (m2 && !strstr(out, m2)) ok = FALSE;
  if (!ok) "status %d\n%s\n", res, out;

  Free(out);
  CleanupFwdInitTest;
  return ok;
}

Bool TestGlobal(U0)
{
  return CompileFailsExactly(
"class Foo;
Foo f = {1};
U0 Main() {}
", 1, MSG_INCOMPLETE, "2:9");
}

Bool TestLocal(U0)
{
  return CompileFailsExactly(
"class Foo;
U0 Main() { Foo f = {1, 2}; }
", 1, MSG_INCOMPLETE, "2:21");
}

/* Reported once, at the outer `{`, not once per element */
Bool TestArray(U0)
{
  return CompileFailsExactly(
"class Foo;
U0 Main() { Foo a[2] = {{1},{2}}; }
", 1, MSG_INCOMPLETE, "2:24");
}

Bool TestArrayUnsized(U0)
{
  return CompileFailsExactly(
"class Foo;
Foo a[] = {{1},{2},{3}};
U0 Main() {}
", 1, MSG_INCOMPLETE, NULL);
}

/* Each bad initialiser is reported and the code after it still parses */
Bool TestSeveral(U0)
{
  return CompileFailsExactly(
"class Foo;
Foo g = {1};
U0 Main() {
  Foo a[2] = {{1},{2}};
  Foo b = {3, 4};
  I64 ok = 5;
}
", 3, MSG_INCOMPLETE, "5:11");
}

Bool TestDefinedLater(U0)
{
  Fwd125 l = {5, 6};
  return fwd_125_global.a == 3 && fwd_125_global.b == 4 &&
         l.a == 5 && l.b == 6 && sizeof(Fwd125) == 16 &&
         fwd_125_ptr == NULL;
}

I32 Main()
{
  "Test - Forward declared class initialiser: \n";
  I64 tests = 6;
  I64 correct = 0;

  if (TestGlobal()) correct++;
  else "Failed - TestGlobal()\n";

  if (TestLocal()) correct++;
  else "Failed - TestLocal()\n";

  if (TestArray()) correct++;
  else "Failed - TestArray()\n";

  if (TestArrayUnsized()) correct++;
  else "Failed - TestArrayUnsized()\n";

  if (TestSeveral()) correct++;
  else "Failed - TestSeveral()\n";

  if (TestDefinedLater()) correct++;
  else "Failed - TestDefinedLater()\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
