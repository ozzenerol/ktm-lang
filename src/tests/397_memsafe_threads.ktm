import cstr, fs, libc, proc, test;

/* `ktm -jit -Memsafe` crashed (SIGSEGV) as soon as a pthread MAlloc'd:
 * the allocation-site walk followed frame pointers past the end of the
 * thread's chain, which, unlike the main thread's, doesn't end in a null
 * frame pointer. The walk now stays inside the calling thread's stack,
 * and the tracking tables, shared by every thread, are locked. Leaks and
 * double frees in threads are still reported, with their sites. */

#define MT_PROG "./ktm-397-mt.ktm"
#define MT_OUT  "./ktm-397-mt.out"
#define MT_ERR  "./ktm-397-mt.err"
#define MT_RUN "$(command -v timeout >/dev/null 2>&1 && echo timeout 120) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit -Memsafe ./ktm-397-mt.ktm > ./ktm-397-mt.out 2> ./ktm-397-mt.err"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else {
        printf("\n  failed: %s\n", what);
        proc.shell("cat " MT_OUT " " MT_ERR " | head -10");
    }
}

/* Run `src` under -Memsafe: true if ktm exited 0 */
bool runMt(u8 *src) {
    fs.writeAll(MT_PROG, cstr.view(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    return proc.shell(MT_RUN) == 0;
}

/* `what` (an extended regex) is on `times` lines of the report */
bool errHas(u8 *what, i64 times = 1) {
    u8 cmd[512];
    cstr.formatTo(cmd, "[ \"$(grep -E -c -- '%s' " MT_ERR ")\" = %d ]", what, times);
    return proc.shell(cmd) == 0;
}

i32 main() {
    printf("Test - -Memsafe in threads: ");

    /* 8 threads x 2000 rounds of MAlloc/mem.realloc/CAlloc/Free, all freed:
     * no crash and nothing reported */
    check(runMt(
"import libc, mem;

void *churn(void *arg)
{
  i64 i, n = (i64)arg;
  u8 *keep[8];
  for (i = 0; i < 8; i++) { keep[i] = mem.alloc(16 + i); }
  for (i = 0; i < n; i++) {
    u8 *p = mem.alloc(64 + i % 32);
    p[0] = i;
    p = mem.realloc(p, 128);
    u8 *q = mem.allocZero(24);
    mem.free(p);
    mem.free(q);
  }
  for (i = 0; i < 8; i++) { mem.free(keep[i]); }
  return null;
}
i32 main()
{
  libc.pthread_t *th[8];
  i64 i;
  for (i = 0; i < 8; i++) { libc.pthread_create(&th[i], null, &churn, (void *)2000); }
  for (i = 0; i < 8; i++) { libc.pthread_join(th[i]); }
  printf(\"done\\n\");
  return 0;
}
"), "8 threads allocate and free");
    check(proc.shell("[ \"$(cat " MT_OUT ")\" = done ] && [ ! -s " MT_ERR " ]") == 0,
                "correct threaded code reports nothing");

    /* a leak in one thread, a double free in another */
    check(runMt(
"import libc, mem;

void *leaker(void *arg)
{
  u8 *lost = mem.alloc(48);
  lost[0] = 1;
  return null;
}
void *doubleFree(void *arg)
{
  u8 *p = mem.alloc(40);
  mem.free(p);
  mem.free(p);
  return null;
}
i32 main()
{
  libc.pthread_t *a, *b;
  libc.pthread_create(&a, null, &leaker, null);
  libc.pthread_join(a);
  libc.pthread_create(&b, null, &doubleFree, null);
  libc.pthread_join(b);
  printf(\"done\\n\");
  return 0;
}
"), "leak and double free in threads run");
    /* macOS names the functions with a leading `_` */
    check(errHas("memsafe: 1 allocation\\(s\\) \\(48 bytes\\) never freed") &&
                errHas("in `_?leaker`"), "leak in a thread reported, with its site");
    check(errHas("double free of") && errHas("this free   in `_?doubleFree`"),
                "double free in a thread caught, with its site");

    proc.shell("rm -f " MT_PROG " " MT_OUT " " MT_ERR);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
