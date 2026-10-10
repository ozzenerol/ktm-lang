#include "testhelper.ktm"

/* `#include "."` (a directory) hung forever: open() succeeds on a
 * directory and lexReadfile looped while read() kept failing with
 * EISDIR. It must be reported at the #include and lexing carry on, and
 * a directory given as the source file must be an error, not a hang. */

#define INCDIR_FILE "__136_include_directory.ktm"
#define INCDIR_INC  "__136_include_directory.ktmh"
#define INCDIR_DIR  "__136_include_directory.d"
#define INCDIR_OUT  "__136_include_directory.out"
/* ktm only takes a source file by its extension */
#define INCDIR_SRCDIR "__136_include_directory_dir.ktm"

/* `timeout` is coreutils; without it the command just runs */
#define INCDIR_KTM "$(command -v timeout >/dev/null && echo timeout 10) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U0 CleanupIncDirTest(U0)
{
  System("chmod 644 " INCDIR_INC " 2>/dev/null; rm -rf " INCDIR_FILE " " INCDIR_INC " " INCDIR_DIR " " INCDIR_SRCDIR " " INCDIR_OUT " ./a.out");
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

/* Run ktm with `args` > INCDIR_OUT. With `count` 0 it must compile
 * cleanly; otherwise it must exit 1 with `want` in its output and, when
 * `count` > 0, exactly `count` errors. */
Bool RunKtm(U8 *args, I64 count, U8 *want)
{
  U8 *cmd, *out;
  I64 res;
  Bool ok = TRUE;

  cmd = MStrPrint(INCDIR_KTM "%s > " INCDIR_OUT " 2>&1", args);
  res = System(cmd);
  Free(cmd);

  out = FileRead(INCDIR_OUT);
  if (!out) return FALSE;

  if (count == 0) {
    if (res != 0) ok = FALSE;
  } else {
    /* System returns the exit code; `timeout` exits 124 on a hang */
    if (res != 1) ok = FALSE;
    if (!strstr(out, want)) ok = FALSE;
    if (count > 0 && CountErrors(out) != count) ok = FALSE;
  }
  if (!ok) "exit status %d\n%s\n", res, out;

  Free(out);
  return ok;
}

Bool Compile(U8 *source, I64 count, U8 *want)
{
  Bool ok;
  if (!FileWrite(INCDIR_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  ok = RunKtm(INCDIR_FILE, count, want);
  Rm(INCDIR_FILE);
  return ok;
}

/* Reported at the #include, and the rest of the file is still lexed and
 * parsed: the bad declaration after it is reported too */
Bool TestIncludeDot(U0)
{
  return Compile(
"#include \".\"
NoSuchType y;
U0 Main() { \"hi\\n\"; }
", 2, "error: #include: cannot read '.'");
}

Bool TestIncludeDirectory(U0)
{
  Bool ok;
  System("mkdir -p " INCDIR_DIR);
  ok = Compile(
"#include \"" INCDIR_DIR "\"
U0 Main() { \"hi\\n\"; }
", 1, "error: #include: cannot read '" INCDIR_DIR "'");
  System("rm -rf " INCDIR_DIR);
  return ok;
}

/* An unreadable file is caught before the read (as "cannot open"); root
 * can read it anyway, so the check is skipped there */
Bool TestIncludeUnreadable(U0)
{
  Bool ok = TRUE;
  if (System("test \"$(id -u)\" = 0") == 0) return TRUE;
  System("echo 'I64 x = 1;' > " INCDIR_INC " && chmod 000 " INCDIR_INC);
  ok = Compile(
"#include \"" INCDIR_INC "\"
U0 Main() { \"hi\\n\"; }
", 1, "error: #include: cannot open '" INCDIR_INC "'");
  System("chmod 644 " INCDIR_INC "; rm -f " INCDIR_INC);
  return ok;
}

/* An empty file is a valid (empty) include */
Bool TestIncludeEmpty(U0)
{
  Bool ok;
  System(": > " INCDIR_INC);
  ok = Compile(
"#include \"" INCDIR_INC "\"
U0 Main() { \"hi\\n\"; }
", 0, NULL);
  Rm(INCDIR_INC);
  return ok;
}

/* The source file itself being a directory */
Bool TestSourceIsDirectory(U0)
{
  Bool ok;
  System("mkdir -p " INCDIR_SRCDIR);
  ok = RunKtm(INCDIR_SRCDIR, -1,
              "Failed to read file '" INCDIR_SRCDIR "': Is a directory");
  System("rm -rf " INCDIR_SRCDIR);
  return ok;
}

I32 Main()
{
  "Test - #include of a directory: ";
  I64 correct = 0, tests = 0;

  CleanupIncDirTest;
  tests++; if (TestIncludeDot)        correct++;
  tests++; if (TestIncludeDirectory)  correct++;
  tests++; if (TestIncludeUnreadable) correct++;
  tests++; if (TestIncludeEmpty)      correct++;
  tests++; if (TestSourceIsDirectory) correct++;
  CleanupIncDirTest;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
