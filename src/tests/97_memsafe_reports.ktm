import cstr, fs, libc, mem, proc, test;

/* `ktm -jit -Memsafe` reported leaks and double frees in correct code:
 * its pointer table (a containers.c Map) ended every lookup at the
 * first deleted slot, so after a few Frees the live blocks behind a
 * tombstone could no longer be found - they were "never freed", and
 * freeing them again looked like a wild free. A freed block's address
 * could also be reused by a MAlloc inside libktm while still in the
 * recently-freed ring, so freeing that looked like a double free.
 * Correct programs must report nothing; real leaks, double frees and
 * wild frees must be reported exactly. */

#define MS_PROG "./ktm-97-memsafe.ktm"
#define MS_OUT  "./ktm-97-memsafe.out"
#define MS_ERR  "./ktm-97-memsafe.err"
/* A single literal: adjacent strings don't concatenate inside #define */
#define MS_RUN "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit -Memsafe ./ktm-97-memsafe.ktm > ./ktm-97-memsafe.out 2> ./ktm-97-memsafe.err"
#define MS_REPL "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -repl < ./ktm-97-memsafe.ktm > ./ktm-97-memsafe.out 2> ./ktm-97-memsafe.err"

void cleanupMemsafeTest(void) {
    proc.shell("rm -f " MS_PROG " " MS_OUT " " MS_ERR);
}

/* Write `source` and run it under -Memsafe; true if ktm exited 0 */
bool runMemsafe(u8 *source, u8 *cmd = MS_RUN) {
    fs.writeAll(MS_PROG, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    return proc.shell(cstr.view(cmd)) == 0;
}

/* The program's stdout must be exactly `expected` */
bool stdoutIs(u8 *expected) {
    u8 *out = fs.readAll(MS_OUT).take();
    bool ok;
    if (!out) { return false; }
    ok = cstr.cmp(out, expected) == 0;
    if (!ok) { printf("got: '%s'\n", out); }
    mem.free(out);
    return ok;
}

/* A shell test over the captured report */
bool check(u8 *cmd) {
    if (proc.shell(cstr.view(cmd)) == 0) { return true; }
    proc.shell("cat " MS_ERR);
    return false;
}

i32 main() {
    printf("Test - -Memsafe reports: ");
    i64 tests = 6;
    i64 correct = 0;

    cleanupMemsafeTest();

    /* Correct code: thousands of blocks freed in mixed order, every
     * allocator entry point, stdlib strings and containers with
     * destructors. Nothing may be reported. */
    if (runMemsafe(
"import cstr, map, mem, vec;

void freeIt(void *p) { mem.free(p); }
void main()
{
  i64 i, j;
  u8 *p, *s, **arr = mem.alloc(3000 * sizeof(u8 *));
  for (j = 0; j < 3; j++) {
    for (i = 0; i < 3000; i++) { arr[i] = mem.alloc(i % 97 + 1); }
    for (i = 0; i < 3000; i += 2) { mem.free(arr[i]); }
    for (i = 1; i < 3000; i += 2) { mem.free(arr[i]); }
  }
  mem.free(arr);
  p = mem.allocZero(64); mem.free(p);
  p = mem.alloc(10); p = mem.realloc(p, 1000); p = mem.realloc(p, 4); mem.free(p);
  p = mem.realloc(null, 32); p = mem.realloc(p, 0); mem.free(p);
  p = mem.alloc(0); mem.free(p);
  p = mem.alloc(40); if (mem.size(p) != 40) { printf(\"bad MSize\\n\"); } mem.free(p);
  mem.free(null);
  s = cstr.dup(\"hello\"); mem.free(s);
  s = cstr.formatTo(null, \"%d\", 42); mem.free(s);
  s = cstr.format(\"%d\", 7); mem.free(s);
  s = cstr.dup(\"a\"); s = cstr.appendFormat(s, \"%d\", 123); mem.free(s);
  vec.Vec<void *> v = vec.new<void *>();
  for (i = 0; i < 1000; i++) { v.push(mem.alloc(24)); }
  for (auto p : v) { freeIt(p); }
  v.free();
  map.Map<u8[], void *> m = map.new<u8[], void *>();
  u8 kb[16];
  for (i = 0; i < 2000; i++) {
    m.set(cstr.view(cstr.formatTo(kb, \"k%d\", i)), mem.allocZero(8));
  }
  i64 it = 0;
  void *val;
  while (m.next(&it, null, &val)) { mem.free(val); }
  m.free();
  printf(\"ok\\n\");
}
") && stdoutIs("ok\n") && check("test ! -s " MS_ERR)) { correct++; }
    else { printf("Failed - correct program reported problems\n"); }

    /* Exactly the 3 blocks leaked among 2000 freed ones, with sizes */
    if (runMemsafe(
"import mem;

void main()
{
  i64 i;
  u8 *keep[3];
  for (i = 0; i < 1000; i++) { mem.free(mem.alloc(i + 1)); }
  keep[0] = mem.alloc(10);
  keep[1] = mem.allocZero(20);
  keep[2] = mem.realloc(mem.alloc(5), 30);
  for (i = 0; i < 1000; i++) { mem.free(mem.alloc(i + 1)); }
}
") && check("grep -qx 'memsafe: 3 allocation(s) (60 bytes) never freed' " MS_ERR
                        " && test $(grep -c ' bytes  ' " MS_ERR ") -eq 3"
                        " && grep -q ' 30 bytes  .*line 10$' " MS_ERR
                        " && grep -q ' 20 bytes  .*line 9$' " MS_ERR
                        " && grep -q ' 10 bytes  .*line 8$' " MS_ERR
                        " && ! grep -q 'double free\\|never heap' " MS_ERR)) { correct++; }
    else { printf("Failed - leak of 3 blocks\n"); }

    /* One double free, reported once and skipped; no leak */
    if (runMemsafe(
"import mem;

void main()
{
  i64 i;
  for (i = 0; i < 500; i++) { mem.free(mem.alloc(8)); }
  u8 *p = mem.alloc(5);
  mem.free(p);
  mem.free(p);
  printf(\"after\\n\");
}
") && stdoutIs("after\n") &&
            check("test $(grep -c 'double free of .* (5 bytes)' " MS_ERR ") -eq 1"
                        " && ! grep -q 'never freed\\|never heap' " MS_ERR)) { correct++; }
    else { printf("Failed - double free\n"); }

    /* Freeing a stack array and a global: wild frees, skipped */
    if (runMemsafe(
"import mem;

i64 g[4];
void main()
{
  i64 x[4];
  mem.free(x);
  mem.free(g);
  printf(\"after\\n\");
}
") && stdoutIs("after\n") &&
            check("test $(grep -c 'was never heap-allocated' " MS_ERR ") -eq 2"
                        " && ! grep -q 'never freed\\|double free' " MS_ERR)) { correct++; }
    else { printf("Failed - wild free\n"); }

    /* The REPL always tracks: churn leaves nothing live, a double free
     * is reported once, replHeap shows the one real live block */
    if (runMemsafe(
"import mem;

i64 i;
u8 *a[300];
for (i = 0; i < 300; i++) a[i] = mem.alloc(8);
for (i = 0; i < 300; i++) mem.free(a[i]);
u8 *p = mem.alloc(7);
u8 *q = mem.alloc(9);
mem.free(q);
mem.free(q);
replHeap;
", MS_REPL) && check("grep -q '^heap: 1 live allocation(s), 7 bytes$' " MS_OUT
                                             " && test $(grep -c 'double free of .* (9 bytes)' " MS_ERR ") -eq 1")) {
        correct++;
                                             } else { printf("Failed - repl\n"); }

    /* Exact leak count and total over thousands of survivors */
    if (runMemsafe(
"import mem;

void main()
{
  i64 i;
  u8 *p;
  for (i = 0; i < 4000; i++) {
    p = mem.alloc(8);
    if (i % 4) { mem.free(p); }
  }
}
") && check("grep -qx 'memsafe: 1000 allocation(s) (8000 bytes) never freed' " MS_ERR)) {
        correct++;
} else { printf("Failed - leak count over thousands\n"); }

    cleanupMemsafeTest();
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
