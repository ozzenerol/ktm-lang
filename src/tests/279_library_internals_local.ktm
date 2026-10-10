#include "testhelper.ktm"

/* Library functions that std.ktmh doesn't declare are internal helpers,
 * but libktm exported every non-static function as a weak symbol, so a
 * program with a function of the same name replaced the library's own
 * when compiled: its `RoundUpToNextPowerOf2` broke every IntMap, its
 * `JsonParseObject` broke JsonParse, its `_IntCmp` broke IntVecSort.
 * libktm is one translation unit, so when it is built only what std.ktmh
 * declares is exported now and every other symbol is local. Replacing a
 * std.ktmh function still works (test 197). Compiled and JIT. */

#define LP_FILE "__279_private.ktm"
#define LP_EXE  "__279_private.exe"
#define LP_OUT  "__279_private.out"
#define LP_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
#define LP_KTM LP_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U0 Cleanup(U0)
{
  System("rm -f " LP_FILE " " LP_EXE " " LP_OUT " ./a.out");
}

/* Compile `source` and run it (or run it in the JIT); its output must
 * contain `want` */
Bool Runs(Bool jit, U8 *source, U8 *want)
{
  U8 *out;
  I64 res;
  Cleanup;
  FileWrite(LP_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  if (jit) {
    res = System(LP_KTM "-jit " LP_FILE " > " LP_OUT " 2>&1");
  } else {
    res = System(LP_KTM LP_FILE " -o " LP_EXE " > " LP_OUT " 2>&1 && "
                 LP_TIMEOUT "./" LP_EXE " >> " LP_OUT " 2>&1");
  }
  out = FileRead(LP_OUT);
  Bool ok = res == 0 && out && strstr(out, want);
  if (!ok) "jit=%d: exit status %d\n%s\n", jit, res, out;
  Free(out);
  Cleanup;
  return ok;
}

/* Internal helpers of the hashtable, json, vector and date modules */
U8 *src_internal =
"U64 RoundUpToNextPowerOf2(U64 v) { return 0; }
StrMapNode *StrMapNodeNew(U8 *k, I64 n, U0 *v) { return NULL; }
I64 JsonParseObject(I64 x) { return 0; }
I64 _IntCmp(I64 a, I64 b) { return 0; }
U0 CivilFromDays(I64 z, I64 *y, I64 *m, I64 *d) { *y = 1; *m = 1; *d = 1; }
U0 Main()
{
  IntMap *im = IntMapNew(100);
  for (I64 i = 0; i < 50; i++) IntMapSet(im, i, (i + 1)(U0 *));
  StrMap *sm = StrMapNew();
  StrMapSet(sm, \"k\", 7(U0 *));
  Json *j = JsonParse(\"{\\\"a\\\": [3, 1, 2]}\");
  U8 *js = JsonToString(j);
  IntVec *v = IntVecNew();
  IntVecPush(v, 3); IntVecPush(v, 1); IntVecPush(v, 2);
  IntVecSort(v);
  cTimespec ts;
  ts.tv_sec = 86400 * 365;
  ts.tv_nsec = 0;
  U8 iso[64];
  IsoFromTimespec(&ts, iso);
  \"cap=%d get=%d str=%d json=%s sorted=%d%d%d iso=%s own=%d\\n\",
    im->capacity, IntMapGet(im, 49), StrMapGet(sm, \"k\"), js,
    v->entries[0], v->entries[1], v->entries[2], iso,
    RoundUpToNextPowerOf2(5);
}
";

/* Replacing a library function std.ktmh declares still works */
U8 *src_replace =
"CDate Now() { return 42; }
U0 Main() { \"now=%d\\n\", Now; }
";

I32 Main()
{
  "Test - library internals are local symbols: ";
  I64 tests = 0;
  I64 correct = 0;

  for (I64 jit = 0; jit < 2; jit++) {
    tests++;
    if (Runs(jit, src_internal,
             "cap=128 get=50 str=7 json={\"a\": [3,1,2]} sorted=123 iso=1971-01-01T00:00:00.000000000Z own=0"))
      correct++;
    else "Failed - internal helpers (jit=%d)\n", jit;
    tests++;
    if (Runs(jit, src_replace, "now=42")) correct++;
    else "Failed - replacing a std.ktmh function (jit=%d)\n", jit;
  }

  /* In the archive: an internal function is local (`t`), an API one
   * global; nothing std.ktmh lacks is exported. Skipped without nm. */
  tests++;
  I64 res = System("command -v nm >/dev/null 2>&1 || exit 0; "
      "lib=${KTM_INSTALL_DIR:-../../build/prefix}/lib/libktm.a; "
      "[ -f \"$lib\" ] || exit 0; "
      "nm \"$lib\" | grep -qE ' t _?RoundUpToNextPowerOf2$' && "
      "nm \"$lib\" | grep -qE ' [WTV] _?IntMapNew$' && "
      "! nm \"$lib\" | grep -qE ' [WTVD] _?(RoundUpToNextPowerOf2|JsonParseObject|_IntCmp)$'");
  if (res == 0) correct++;
  else "Failed - nm\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
