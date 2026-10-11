import fs, libc, mem, proc, str, test;

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

void cleanup(void) {
    proc.shell("rm -f " SL_FILE " " SL_EXE " " SL_OUT " ./a.out");
}

/* Compile `source` and run it (or run it in the JIT); its output must
 * contain `want` */
bool runs(bool jit, u8 *source, u8 *want) {
    u8 *out;
    i64 res;
    cleanup();
    fs.writeAll(SL_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    if (jit) {
        res = proc.shell(SL_KTM "-jit " SL_FILE " > " SL_OUT " 2>&1");
    } else {
        res = proc.shell(SL_KTM SL_FILE " -o " SL_EXE " > " SL_OUT " 2>&1 && "
                                 SL_TIMEOUT "./" SL_EXE " >> " SL_OUT " 2>&1");
    }
    out = fs.readAll(SL_OUT);
    bool ok = res == 0 && out && libc.strstr(out, want);
    if (!ok) { printf("jit=%d: exit status %d\n%s\n", jit, res, out); }
    mem.free(out);
    cleanup();
    return ok;
}

/* A library-internal name, static in the library */
u8 *srcCount =
"import json, mem;

i64 JsonNumberLen(void *p, bool *is_int) { return 0; }
void main()
{
  json.Value *j = json.parse(\"[12]\");
  u8 *s = json.toStr(j);
  printf(\"ok=%d %s\\n\", json.ok(j), s);
  mem.free(s);
  json.release(j);
}
";

u8 *srcWs =
"import json;

i64 getNextNonWhitespaceIdx(u8 *p) { return 42; }
void main()
{
  json.Value *j = json.parse(\"  [1]\");
  printf(\"mine=%d ok=%d\\n\", getNextNonWhitespaceIdx(\"x\"), json.ok(j));
  json.release(j);
}
";

/* A program's static function named like a library function used inside
 * the library (MStrPrint calls StrNew) */
u8 *srcUserStatic =
"import mem, str;

static u8 *StrNew(u8 *buf) { return \"mine\"; }
static i64 gCount = 7;
static i64 twice(i64 x) { return x * 2; }
void main()
{
  u8 *s = str.format(\"%d\", 5);
  printf(\"lib=%s own=%s twice=%d count=%d\\n\", s, StrNew(\"x\"), twice(4), gCount);
  mem.free(s);
}
";

/* Replacing a library function without `static` still works */
u8 *srcReplace =
"import date;

date.Date Now() { return 42; }
void main() { printf(\"now=%d\\n\", Now)(); }
";

i32 main() {
    printf("Test - static functions are local symbols: ");
    i64 tests = 0;
    i64 correct = 0;

    for (i64 jit = 0; jit < 2; jit++) {
        tests++;
        if (runs(jit, srcCount, "ok=1 [12]")) { correct++; }
        else { printf("Failed - library static (jit=%d)\n", jit); }
        tests++;
        if (runs(jit, srcWs, "mine=42 ok=1")) { correct++; }
        else { printf("Failed - library internal (jit=%d)\n", jit); }
        tests++;
        if (runs(jit, srcUserStatic, "lib=5 own=mine twice=8 count=7")) { correct++; }
        else { printf("Failed - program static (jit=%d)\n", jit); }
        tests++;
        if (runs(jit, srcReplace, "now=42")) { correct++; }
        else { printf("Failed - replacing a library function (jit=%d)\n", jit); }
    }

    /* In the archive: a static function is a local (`t`) symbol, a public
     * one stays weak (`W`). Skipped without nm. */
    tests++;
    i64 res = proc.shell("command -v nm >/dev/null 2>&1 || exit 0; "
            "lib=${KTM_INSTALL_DIR:-../../build/prefix}/lib/libktm.a; "
            "[ -f \"$lib\" ] || exit 0; "
            "nm \"$lib\" | grep -E ' [Tt] _?JsonNumberLen$' | grep -q ' t ' && "
            "nm \"$lib\" | grep -qE ' [WVT] _?JsonParse$' && "
            "! nm \"$lib\" | grep -qE ' [TWV] _?JsonNumberLen$'");
    if (res == 0) { correct++; }
    else { printf("Failed - nm\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
