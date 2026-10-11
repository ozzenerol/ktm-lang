import cstr, fs, libc, mem, proc, test;

/* Library functions are weak definitions in libktm; `static` functions
 * and file-scope `static` variables are local symbols, in the library and
 * in programs. With the stdlib in modules a library function's symbol is
 * the module's (_K4json...), so a program's function or static function
 * with the short name of a library internal (json's static jsonNumberLen,
 * str's dup) or of a public function (date.now) lives next to it: neither
 * replaces the other. Compiled and JIT. */

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
    fs.writeAll(SL_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    if (jit) {
        res = proc.shell(SL_KTM "-jit " SL_FILE " > " SL_OUT " 2>&1");
    } else {
        res = proc.shell(SL_KTM SL_FILE " -o " SL_EXE " > " SL_OUT " 2>&1 && "
                                 SL_TIMEOUT "./" SL_EXE " >> " SL_OUT " 2>&1");
    }
    out = fs.readAll(SL_OUT).take();
    bool ok = res == 0 && out && libc.strstr(out, want);
    if (!ok) { printf("jit=%d: exit status %d\n%s\n", jit, res, out); }
    mem.free(out);
    cleanup();
    return ok;
}

/* A library-internal name, static in the library */
u8 *srcCount =
"import json, mem;

i64 jsonNumberLen(void *p, bool *is_int) { return 0; }
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

i64 jsonAdvanceWhitespace(u8 *p) { return 42; }
void main()
{
  json.Value *j = json.parse(\"  [1]\");
  printf(\"mine=%d ok=%d\\n\", jsonAdvanceWhitespace(\"x\"), json.ok(j));
  json.release(j);
}
";

/* A program's static function named like a library function used inside
 * the library (cstr.format) */
u8 *srcUserStatic =
"import cstr, mem;

static u8 *dup(u8 *buf) { return \"mine\"; }
static i64 gCount = 7;
static i64 twice(i64 x) { return x * 2; }
void main()
{
  u8 *s = cstr.format(\"%d\", 5);
  printf(\"lib=%s own=%s twice=%d count=%d\\n\", s, dup(\"x\"), twice(4), gCount);
  mem.free(s);
}
";

/* A program's own now() next to date.now() */
u8 *srcOwnNow =
"import date;

i64 now() { return 42; }
void main() { printf(\"now=%d lib=%d\\n\", now(), date.now() != 42); }
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
        if (runs(jit, srcOwnNow, "now=42 lib=1")) { correct++; }
        else { printf("Failed - own now next to date.now (jit=%d)\n", jit); }
    }

    /* In the archive: a static function is a local (`t`) symbol, a public
     * one stays weak (`W`). Skipped without nm. */
    tests++;
    i64 res = proc.shell("command -v nm >/dev/null 2>&1 || exit 0; "
            "lib=${KTM_INSTALL_DIR:-../../build/prefix}/lib/libktm.a; "
            "[ -f \"$lib\" ] || exit 0; "
            "nm \"$lib\" | grep -E ' [Tt] _?_K4json13jsonNumberLen$' | grep -q ' t ' && "
            "nm \"$lib\" | grep -qE ' [WVT] _?_K4json5parse$' && "
            "! nm \"$lib\" | grep -qE ' [TWV] _?_K4json13jsonNumberLen$'");
    if (res == 0) { correct++; }
    else { printf("Failed - nm\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
