import cstr, fs, libc, mem, proc, test;

/* A program with a function named like a library internal used to
 * replace the library's own when compiled: its `RoundUpToNextPowerOf2`
 * broke every map.IntMap, its `JsonParseObject` broke json.parse, its
 * `_IntCmp` broke vec.intSort. With the stdlib in modules every library
 * symbol is the module's (_K3map21roundUpToNextPowerOf2), so a program's
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
  map.IntMap *im = map.intNew(100);
  for (i64 i = 0; i < 50; i++) { im.set(i, (void *)(i + 1)); }
  map.StrMap *sm = map.strNew();
  sm.set(\"k\", (void *)7);
  json.Value *j = json.parse(\"{\\\"a\\\": [3, 1, 2]}\");
  u8 *js = json.toStr(j).take();
  vec.IntVec *v = vec.intNew();
  v.push(3); v.push(1); v.push(2);
  v.sort();
  libc.timespec ts;
  ts.tv_sec = 86400 * 365;
  ts.tv_nsec = 0;
  u8 iso[64];
  date.isoFromTimespec(&ts, iso);
  printf(\"cap=%d get=%d str=%d json=%s sorted=%d%d%d iso=%s own=%d\\n\",
    im.capacity, im.get(49), sm.get(\"k\"), js,
    v.entries[0], v.entries[1], v.entries[2], iso,
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
                         "cap=128 get=50 str=7 json={\"a\": [3,1,2]} sorted=123 iso=1971-01-01T00:00:00.000000000Z own=0")) {
            correct++;
                         } else { printf("Failed - internal helpers (jit=%d)\n", jit); }
    }

    /* In the archive the internals are module symbols; none has its bare
     * name. Skipped without nm. */
    tests++;
    i64 res = proc.shell("command -v nm >/dev/null 2>&1 || exit 0; "
            "lib=${KTM_INSTALL_DIR:-../../build/prefix}/lib/libktm.a; "
            "[ -f \"$lib\" ] || exit 0; "
            "nm \"$lib\" | grep -qE ' [WTt] _?_K3map21roundUpToNextPowerOf2$' && "
            "nm \"$lib\" | grep -qE ' [WTV] _?_K3map6intNew$' && "
            "! nm \"$lib\" | grep -qE ' [WTtVD] _?(roundUpToNextPowerOf2|jsonParseObject|intCmp|civilFromDays)$'");
    if (res == 0) { correct++; }
    else { printf("Failed - nm\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
