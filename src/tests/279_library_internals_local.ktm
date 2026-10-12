import cstr, fs, libc, mem, proc, test;

/* A program with a function named like a library internal used to
 * replace the library's own when compiled: its `RoundUpToNextPowerOf2`
 * broke every map, its `JsonParseObject` broke json.parse, its
 * `_IntCmp` broke vec.intSort. With the stdlib in modules every library
 * symbol is the module's (_K4json15jsonParseObject), so a program's
 * roundUpToNextPowerOf2, jsonParseObject, intCmp and civilFromDays live
 * next to the modules' private ones and replace nothing. Compiled and
 * JIT. */

#define LP_FILE "__279_private.ktm"
#define LP_EXE  "__279_private.exe"
#define LP_OUT  "__279_private.out"
#define LP_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
#define LP_KTM LP_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

void cleanup(void) {
    proc.shell("rm -f " LP_FILE " " LP_EXE " " LP_OUT " ./a.out");
}

/* Compile `source` and run it (or run it in the JIT); its output must
 * contain `want` */
bool runs(bool jit, u8 *source, u8 *want) {
    u8 *out;
    i64 res;
    cleanup();
    fs.writeAll(LP_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    if (jit) {
        res = proc.shell(LP_KTM "-jit " LP_FILE " > " LP_OUT " 2>&1");
    } else {
        res = proc.shell(LP_KTM LP_FILE " -o " LP_EXE " > " LP_OUT " 2>&1 && "
                                 LP_TIMEOUT "./" LP_EXE " >> " LP_OUT " 2>&1");
    }
    out = fs.readAll(LP_OUT).take();
    bool ok = res == 0 && out && libc.strstr(out, want);
    if (!ok) { printf("jit=%d: exit status %d\n%s\n", jit, res, out); }
    mem.free(out);
    cleanup();
    return ok;
}

/* Internal helpers of the hashtable, json, vector and date modules */
u8 *srcInternal =
"import date, json, libc, map, vec;

u64 roundUpToNextPowerOf2(u64 v) { return 0; }
i64 jsonParseObject(i64 x) { return 0; }
i64 intCmp(i64 a, i64 b) { return 0; }
void civilFromDays(i64 z, i64 *y, i64 *m, i64 *d) { *y = 1; *m = 1; *d = 1; }
void main()
{
  map.Map<i64, i64> im = map.new<i64, i64>(100);
  for (i64 i = 0; i < 50; i++) { im.set(i, i + 1); }
  map.Map<u8[], i64> sm = map.new<u8[], i64>();
  sm.set(\"k\", 7);
  json.Value *j = json.parse(\"{\\\"a\\\": [3, 1, 2]}\");
  u8 *js = json.toStr(j).take();
  vec.Vec<i64> v = vec.new<i64>();
  v.push(3); v.push(1); v.push(2);
  v.sortAsc();
  libc.timespec ts;
  ts.tv_sec = 86400 * 365;
  ts.tv_nsec = 0;
  String iso = date.isoFromTimespec(&ts);
  printf(\"cap=%d get=%d str=%d json=%s sorted=%d%d%d iso=%s own=%d\\n\",
    im.len, im.getOr(49, 0), sm.getOr(\"k\", 0), js,
    v.get(0), v.get(1), v.get(2), iso,
    roundUpToNextPowerOf2(5));
}
";

i32 main() {
    printf("Test - library internals are local symbols: ");
    i64 tests = 0;
    i64 correct = 0;

    for (i64 jit = 0; jit < 2; jit++) {
        tests++;
        if (runs(jit, srcInternal,
                         "cap=50 get=50 str=7 json={\"a\": [3,1,2]} sorted=123 iso=1971-01-01T00:00:00.000000000Z own=0")) {
            correct++;
                         } else { printf("Failed - internal helpers (jit=%d)\n", jit); }
    }

    /* In the archive the internals are module symbols; none has its bare
     * name. Skipped without nm. */
    tests++;
    i64 res = proc.shell("command -v nm >/dev/null 2>&1 || exit 0; "
            "lib=${KTM_INSTALL_DIR:-../../build/prefix}/lib/libktm.a; "
            "[ -f \"$lib\" ] || exit 0; "
            "nm \"$lib\" | grep -qE ' [WTt] _?_K4json15jsonParseObject$' && "
            "nm \"$lib\" | grep -qE ' [WTV] _?_K3map6hashOf_l$' && "
            "! nm \"$lib\" | grep -qE ' [WTtVD] _?(roundUpToNextPowerOf2|jsonParseObject|intCmp|civilFromDays)$'");
    if (res == 0) { correct++; }
    else { printf("Failed - nm\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
