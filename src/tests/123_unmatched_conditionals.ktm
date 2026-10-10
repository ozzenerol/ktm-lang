#include "testhelper.ktm"

#define COND_FILE "__123_unmatched_conditionals.ktm"
#define COND_OUT  "__123_unmatched_conditionals.out"

U0 CleanupCondTest(U0)
{
  Rm(COND_FILE);
  Rm(COND_OUT);
  Rm("./a.out");
}

I64 CountErrors(U8 *out)
{
  I64 n = 0;
  U8 *p = out;
  while ((p = strstr(p, "error: "))) {
    n++;
    p++;
  }
  return n;
}

/* Compile `source`, which must fail with exactly `count` errors, among
 * them `msg1` at `loc1` (" --> file:line:col") and, when not NULL,
 * `msg2` at `loc2` and `msg3` at `loc3`. Exactly `count` errors means
 * lexing carried on sensibly after each bad directive. */
Bool CompileReports(U8 *source, I64 count, U8 *msg1, U8 *loc1,
                    U8 *msg2, U8 *loc2, U8 *msg3, U8 *loc3)
{
  U8 *out, *want;
  U8 *msgs[3], *locs[3];
  I64 res, i;
  Bool ok = TRUE;

  msgs[0] = msg1; locs[0] = loc1;
  msgs[1] = msg2; locs[1] = loc2;
  msgs[2] = msg3; locs[2] = loc3;

  CleanupCondTest;

  if (!FileWrite(COND_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " COND_FILE " > " COND_OUT " 2>&1");
  if (res == 0) ok = FALSE;

  out = FileRead(COND_OUT);
  if (!out) {
    CleanupCondTest;
    return FALSE;
  }

  for (i = 0; i < 3; i++) {
    if (!msgs[i]) break;
    want = MStrPrint("error: %s\n --> " COND_FILE ":%s\n", msgs[i], locs[i]);
    if (!strstr(out, want)) ok = FALSE;
    Free(want);
  }
  if (CountErrors(out) != count) ok = FALSE;
  if (!ok) "%s\n", out;

  Free(out);
  CleanupCondTest;
  return ok;
}

Bool TestStrayEndif(U0)
{
  return CompileReports(
"I64 a = 1;
  #endif
U0 Main() { \"%d\\n\", a; }
",
    1, "#endif without #if", "2:3", NULL, NULL, NULL, NULL);
}

Bool TestStrayElse(U0)
{
  return CompileReports(
"#if 1
#endif
#else
U0 Main() {}
",
    1, "#else without #if", "3:1", NULL, NULL, NULL, NULL);
}

/* The stray #elif's condition goes with it, malformed or not */
Bool TestStrayElif(U0)
{
  return CompileReports(
"#elif (zz+
#elifdef FOO
U0 Main() {}
",
    2, "#elif without #if", "1:1", "#elifdef without #if", "2:1",
    NULL, NULL);
}

/* Nameless: false, and the next line is kept (inside the dead branch,
 * so `b` is undefined), not read as the name */
Bool TestIfdefNoName(U0)
{
  return CompileReports(
"#ifdef
I64 b = 2;
#endif
U0 Main() { \"%d\\n\", b; }
",
    2, "Syntax is: #ifdef <TK_IDENT>", "1:1",
    "Variable or function `b` has not been defined", "4:21", NULL, NULL);
}

Bool TestIfndefNoName(U0)
{
  return CompileReports(
"I64 c = 3;
#ifndef
I64 c = 4;
#else
#endif
U0 Main() { \"%d\\n\", c; }
",
    1, "Syntax is: #ifndef <TK_IDENT>", "2:1", NULL, NULL, NULL, NULL);
}

Bool TestIfdefNotName(U0)
{
  return CompileReports(
"#ifdef 42
I64 d;
#endif
U0 Main() {}
",
    1, "Syntax is: #ifdef <TK_IDENT>", "1:8", NULL, NULL, NULL, NULL);
}

/* After a taken branch, and after an untaken one */
Bool TestDoubleElse(U0)
{
  return CompileReports(
"#if 1
I64 e = 5;
#else
I64 f;
#else
I64 g;
#endif
#if 0
#else
I64 h = 8;
 #else
I64 i;
#endif
U0 Main() { \"%d %d\\n\", e, h; }
",
    2, "#else after #else", "5:1", "#else after #else", "11:2", NULL, NULL);
}

Bool TestElifAfterElse(U0)
{
  return CompileReports(
"#if 0
#else
I64 e;
#elif 1
#endif
#ifdef FOO
#else
#elifdef BAR
#endif
U0 Main() {}
",
    2, "#elif after #else", "4:1", "#elifdef after #else", "8:1", NULL, NULL);
}

/* Unterminated: both the dead and the taken kind, nested */
Bool TestUnterminated(U0)
{
  return CompileReports(
"U0 Main() {}
#if 1
  #ifdef FOO
I64 y;
",
    2, "Unterminated #if", "2:1", "Unterminated #if", "3:3", NULL, NULL);
}

/* Nested conditionals in a dead branch are skipped whole: their
 * #endifs don't end the outer one and their conditions (even malformed
 * ones) aren't evaluated */
I64 NestedDead()
{
  I64 r = 0;
#if 0
  #if 1
  r += 1;
  #endif
  r += 2;
  #if (zz+
  #elif
  #endif
  #ifdef
  #endif
#elif 0
  r += 4;
#else
  #if 0
  r += 8;
  #else
  r += 16;
  #endif
  r += 32;
#endif
  return r;
}

/* A taken branch skips the rest, nested #ifs and all */
I64 NestedTaken()
{
  I64 r = 0;
#define NC_TWO 2
#if NC_TWO == 1
  r += 1;
#elif NC_TWO == 2
  r += 2;
  #ifdef NC_TWO
  r += 4;
    #ifndef NC_TWO
  r += 8;
    #elifdef NC_TWO
  r += 16;
    #else
  r += 32;
    #endif
  #endif
#elif 1
  r += 64;
  #if 1
  r += 128;
  #endif
#else
  #if 0
  #else
  r += 256;
  #endif
#endif
#ifjit
  r += 512;
#else
  r += 512;
#endif
  return r;
}

I32 Main()
{
  "Test - unmatched conditional directives: \n";
  I64 tests = 11;
  I64 correct = 0;

  if (TestStrayEndif()) correct++;
  else "Failed - TestStrayEndif()\n";

  if (TestStrayElse()) correct++;
  else "Failed - TestStrayElse()\n";

  if (TestStrayElif()) correct++;
  else "Failed - TestStrayElif()\n";

  if (TestIfdefNoName()) correct++;
  else "Failed - TestIfdefNoName()\n";

  if (TestIfndefNoName()) correct++;
  else "Failed - TestIfndefNoName()\n";

  if (TestIfdefNotName()) correct++;
  else "Failed - TestIfdefNotName()\n";

  if (TestDoubleElse()) correct++;
  else "Failed - TestDoubleElse()\n";

  if (TestElifAfterElse()) correct++;
  else "Failed - TestElifAfterElse()\n";

  if (TestUnterminated()) correct++;
  else "Failed - TestUnterminated()\n";

  if (NestedDead() == 48) correct++;
  else "Failed - NestedDead() = %d\n", NestedDead();

  if (NestedTaken() == 534) correct++;
  else "Failed - NestedTaken() = %d\n", NestedTaken();

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
