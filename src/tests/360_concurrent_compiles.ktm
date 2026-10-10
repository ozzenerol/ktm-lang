#include "testhelper.ktm"

/* ktm wrote its intermediate assembly to the fixed path /tmp/ktm-asm.s,
 * so compiles running at the same time assembled each other's code (or a
 * half-written file). Each compile now gets its own temporary file in
 * $TMPDIR, removed afterwards. */

#define CC_DIR "__360_concurrent"
#define CC_JOBS 6
#define CC_FUNCS 150
#define CC_KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 120) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

/* Program `id`: CC_FUNCS functions, Main prints "prog <id> <sum>". */
U8 *ProgramSource(I64 id)
{
  I64 len = 0, i;
  U8 *src = NULL;
  for (i = 0; i < CC_FUNCS; i++) {
    src = CatLenPrint(src, &len, "I64 Fn%d(I64 x) { return x * 3 + %d; }\n",
                      i, i + id * 1000);
  }
  src = CatLenPrint(src, &len, "U0 Main()\n{\n  I64 s = 0;\n");
  for (i = 0; i < CC_FUNCS; i++) {
    src = CatLenPrint(src, &len, "  s += Fn%d(1);\n", i);
  }
  src = CatLenPrint(src, &len, "  \"prog %d %%d\\n\", s;\n}\n", id);
  return src;
}

I64 ExpectedSum(I64 id)
{
  return CC_FUNCS * (3 + id * 1000) + CC_FUNCS * (CC_FUNCS - 1) / 2;
}

Bool WriteSources()
{
  for (I64 id = 0; id < CC_JOBS; id++) {
    U8 *path = MStrPrint(CC_DIR "/p%d.ktm", id);
    U8 *src = ProgramSource(id);
    Bool ok = FileWrite(path, src, StrLen(src));
    Free(src);
    Free(path);
    if (!ok) return FALSE;
  }
  return TRUE;
}

/* Number of ktm temporary files left in dir */
I64 LeftoverTmpFiles(U8 *dir)
{
  U8 *out = Shlurp("ls -A '%s' | grep -c '^ktm-'", dir);
  I64 n = -1;
  if (out) {
    n = Atoi(out);
    Free(out);
  }
  return n;
}

/* Compile every program at once (`flags` before the source file), then
 * run each binary and check what it printed. */
Bool CompileAllAtOnce(U8 *flags)
{
  U8 *cmd = NULL;
  I64 len = 0, id;
  Bool ok = TRUE;

  for (id = 0; id < CC_JOBS; id++) {
    cmd = CatLenPrint(cmd, &len,
        "TMPDIR=\"$PWD/" CC_DIR "/tmp\" " CC_KTM "%s " CC_DIR "/p%d.ktm -o "
        CC_DIR "/p%d > " CC_DIR "/p%d.log 2>&1 &\n", flags, id, id, id);
  }
  cmd = CatLenPrint(cmd, &len, "wait\n");
  System("rm -f " CC_DIR "/p? " CC_DIR "/p?.log " CC_DIR "/p?.out");
  System(cmd);
  Free(cmd);

  /* Run them at once too: macOS checks every new binary on its first
   * run, slowly, so one at a time would make this the slowest test. */
  cmd = NULL;
  len = 0;
  for (id = 0; id < CC_JOBS; id++) {
    cmd = CatLenPrint(cmd, &len, "./" CC_DIR "/p%d > " CC_DIR "/p%d.out 2>&1 &\n",
                      id, id);
  }
  cmd = CatLenPrint(cmd, &len, "wait\n");
  System(cmd);
  Free(cmd);

  for (id = 0; id < CC_JOBS; id++) {
    U8 *path = MStrPrint(CC_DIR "/p%d.out", id);
    U8 *out = FileRead(path);
    U8 *want = MStrPrint("prog %d %d\n", id, ExpectedSum(id));
    if (!out || StrCmp(out, want)) {
      U8 *log = FileRead(MStrPrint(CC_DIR "/p%d.log", id));
      "program %d: got '%s', want '%s'\n%s\n", id, out, want, log;
      ok = FALSE;
    }
    Free(out);
    Free(want);
    Free(path);
  }
  return ok;
}

I32 Main()
{
  "Test - concurrent compiles use their own temporary files: ";
  I64 tests = 7, correct = 0;

  RmDir(CC_DIR, RM_RECURSE);
  MkDir(CC_DIR);
  MkDir(CC_DIR "/tmp");

  if (WriteSources()) correct++;
  else "Failed - writing the test programs\n";

  /* Two rounds: the old shared file failed most of the time, not always */
  if (CompileAllAtOnce("")) correct++;
  if (CompileAllAtOnce("-DKTM_360")) correct++;

  /* the temporary files are in $TMPDIR and removed on success */
  if (LeftoverTmpFiles(CC_DIR "/tmp") == 0) correct++;
  else "Failed - temporary files left after successful compiles\n";

  /* ... and when linking fails (ktm exits through its panic path) */
  U8 *bad = "extern \"c\" U0 Ktm360NoSuchFn();\nU0 Main() { Ktm360NoSuchFn(); }\n";
  FileWrite(CC_DIR "/bad.ktm", bad, StrLen(bad));
  if (System("TMPDIR=\"$PWD/" CC_DIR "/tmp\" " CC_KTM CC_DIR "/bad.ktm -o " CC_DIR "/bad > /dev/null 2>&1") != 0) correct++;
  else "Failed - linking an undefined function succeeded\n";
  if (LeftoverTmpFiles(CC_DIR "/tmp") == 0) correct++;
  else "Failed - temporary files left after a failed link\n";

  /* TMPDIR is honoured: an unusable one is an error, not /tmp */
  if (System("TMPDIR=\"$PWD/" CC_DIR "/missing\" " CC_KTM CC_DIR "/p0.ktm -o " CC_DIR "/p0 > /dev/null 2>&1") != 0) correct++;
  else "Failed - compile with a missing TMPDIR succeeded\n";

  RmDir(CC_DIR, RM_RECURSE);
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
