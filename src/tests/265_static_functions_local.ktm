#include "testhelper.ktm"

/* Since library functions became weak definitions (so a program can
 * replace one, test 197), every function in libktm was a weak global
 * symbol, `static` ones included. A program defining a function with the
 * name of a library internal silently replaced it when compiled: its own
 * `CountNumberLen` (static in json.ktm; now JsonNumberLen, which the
 * test uses) broke JsonToString, its own
 * `GetNextNonWhitespaceIdx` (an internal helper of JsonParse, now
 * static) broke JsonParse. `static` functions and file-scope `static`
 * variables are now local symbols, in the library and in programs: a
 * program's static function neither replaces a library one of the same
 * name nor is replaced. Replacing a non-static library function still
 * works. Compiled and JIT. */

#define SL_FILE "__265_static.ktm"
#define SL_EXE  "__265_static.exe"
#define SL_OUT  "__265_static.out"
#define SL_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
#define SL_KTM SL_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U0 Cleanup(U0)
{
  System("rm -f " SL_FILE " " SL_EXE " " SL_OUT " ./a.out");
}

/* Compile `source` and run it (or run it in the JIT); its output must
 * contain `want` */
Bool Runs(Bool jit, U8 *source, U8 *want)
{
  U8 *out;
  I64 res;
  Cleanup;
  FileWrite(SL_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  if (jit) {
    res = System(SL_KTM "-jit " SL_FILE " > " SL_OUT " 2>&1");
  } else {
    res = System(SL_KTM SL_FILE " -o " SL_EXE " > " SL_OUT " 2>&1 && "
                 SL_TIMEOUT "./" SL_EXE " >> " SL_OUT " 2>&1");
  }
  out = FileRead(SL_OUT);
  Bool ok = res == 0 && out && strstr(out, want);
  if (!ok) "jit=%d: exit status %d\n%s\n", jit, res, out;
  Free(out);
  Cleanup;
  return ok;
}

/* A library-internal name, static in the library */
U8 *src_count =
"I64 JsonNumberLen(U0 *p, Bool *is_int) { return 0; }
U0 Main()
{
  Json *j = JsonParse(\"[12]\");
  U8 *s = JsonToString(j);
  \"ok=%d %s\\n\", JsonOk(j), s;
  Free(s);
  JsonRelease(j);
}
";

U8 *src_ws =
"I64 GetNextNonWhitespaceIdx(U8 *p) { return 42; }
U0 Main()
{
  Json *j = JsonParse(\"  [1]\");
  \"mine=%d ok=%d\\n\", GetNextNonWhitespaceIdx(\"x\"), JsonOk(j);
  JsonRelease(j);
}
";

/* A program's static function named like a library function used inside
 * the library (MStrPrint calls StrNew) */
U8 *src_user_static =
"static U8 *StrNew(U8 *buf) { return \"mine\"; }
static I64 g_count = 7;
static I64 Twice(I64 x) { return x * 2; }
U0 Main()
{
  U8 *s = MStrPrint(\"%d\", 5);
  \"lib=%s own=%s twice=%d count=%d\\n\", s, StrNew(\"x\"), Twice(4), g_count;
  Free(s);
}
";

/* Replacing a library function without `static` still works */
U8 *src_replace =
"CDate Now() { return 42; }
U0 Main() { \"now=%d\\n\", Now; }
";

I32 Main()
{
  "Test - static functions are local symbols: ";
  I64 tests = 0;
  I64 correct = 0;

  for (I64 jit = 0; jit < 2; jit++) {
    tests++;
    if (Runs(jit, src_count, "ok=1 [12]")) correct++;
    else "Failed - library static (jit=%d)\n", jit;
    tests++;
    if (Runs(jit, src_ws, "mine=42 ok=1")) correct++;
    else "Failed - library internal (jit=%d)\n", jit;
    tests++;
    if (Runs(jit, src_user_static, "lib=5 own=mine twice=8 count=7")) correct++;
    else "Failed - program static (jit=%d)\n", jit;
    tests++;
    if (Runs(jit, src_replace, "now=42")) correct++;
    else "Failed - replacing a library function (jit=%d)\n", jit;
  }

  /* In the archive: a static function is a local (`t`) symbol, a public
   * one stays weak (`W`). Skipped without nm. */
  tests++;
  I64 res = System("command -v nm >/dev/null 2>&1 || exit 0; "
      "lib=${KTM_INSTALL_DIR:-../../build/prefix}/lib/libktm.a; "
      "[ -f \"$lib\" ] || exit 0; "
      "nm \"$lib\" | grep -E ' [Tt] _?JsonNumberLen$' | grep -q ' t ' && "
      "nm \"$lib\" | grep -qE ' [WVT] _?JsonParse$' && "
      "! nm \"$lib\" | grep -qE ' [TWV] _?JsonNumberLen$'");
  if (res == 0) correct++;
  else "Failed - nm\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
