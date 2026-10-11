import fs, libc, mem, proc, str, test;

/* A program may define its own version of a standard-library function
 * (here `Now()`, `public CDate Now();` in std.ktmh). That worked in the
 * JIT, but an AOT build failed to link with "multiple definition of
 * `Now'" as soon as anything else from libktm was used: libktm is one
 * object. Library functions are now weak definitions, so the program's
 * own one wins, as in the JIT. A definition or prototype whose
 * signature differs from an earlier one (the library's included) is an
 * error at its name, in both modes. */

#define RD_FILE "__197_redefine.ktm"
#define RD_EXE  "__197_redefine.exe"
#define RD_OUT  "__197_redefine.out"
/* `timeout` is coreutils; without it the command just runs */
#define RD_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
#define RD_KTM RD_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

void cleanupRedefineTest(void) {
    proc.shell("rm -f " RD_FILE " " RD_EXE " " RD_OUT " ./a.out");
}

/* Compile `source` ahead of time and run it (`jit` false), or run it in
 * the JIT. It must exit with `status` and its output contain `want1`
 * and `want2` (null to skip). */
bool runRedefine(bool jit, u8 *source, i64 status, u8 *want1, u8 *want2) {
    u8 *out;
    i64 res;
    bool ok = true;

    cleanupRedefineTest();
    if (!fs.writeAll(RD_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    if (jit) {
        res = proc.shell(RD_KTM "-jit " RD_FILE " > " RD_OUT " 2>&1");
    } else {
        res = proc.shell(RD_KTM RD_FILE " -o " RD_EXE " > " RD_OUT " 2>&1 && " RD_TIMEOUT "./" RD_EXE " >> " RD_OUT " 2>&1");
    }
    out = fs.readAll(RD_OUT);
    if (!out) {
        cleanupRedefineTest();
        return false;
    }
    if (res != status) { ok = false; }
    if (want1 && !libc.strstr(out, want1)) { ok = false; }
    if (want2 && !libc.strstr(out, want2)) { ok = false; }
    if (!ok) { printf("jit=%d: exit status %d\n%s\n", jit, res, out); }
    mem.free(out);
    cleanupRedefineTest();
    return ok;
}

/* Own Now() plus other library functions (str.len, date.formatDate) */
u8 *rdSameSig =
"import date, mem, str;

date.Date Now()
{
  return 42;
}

void main()
{
  printf(\"now=%d len=%d\\n\", Now(), str.len(\"abc\"));
  u8 *date = date.formatDate(Now());
  printf(\"date=%s\\n\", date);
  mem.free(date);
}
";

u8 *rdLibConflict =
"import str;

u8 *Now()
{
  return \"x\";
}

void main()
{
  printf(\"%s %d\\n\", Now, str.len(\"abc\"));
}
";

void main() {
    printf("Test - redefine a library function: ");
    i64 correct = 0, tests = 0;

    /* Same signature: links in AOT, and both modes call the program's */
    tests++;
    if (runRedefine(false, rdSameSig, 0, "now=42 len=3", "date=01/02/0000")) { correct++; }
    tests++;
    if (runRedefine(true, rdSameSig, 0, "now=42 len=3", "date=01/02/0000")) { correct++; }

    /* An integer return type spelt as the plain integer the intrinsic
     * class is (`i64 class CDate`) is the same signature */
    tests++;
    if (runRedefine(false,
"import str;

i64 Now() { return 7; }
void main() { printf(\"now=%d %d\\n\", Now(), str.len(\"ab\")); }
", 0, "now=7 2", null)) { correct++; }

    /* A different signature from the library's prototype is an error at
     * the definition's name, not a link failure */
    tests++;
    if (runRedefine(false, rdLibConflict, 1,
            "error: conflicting types for `Now`: `u8 *Now(void);` does not match the earlier declaration `CDate Now(void);` at ",
            "std.ktmh:")) { correct++; }
    tests++;
    if (runRedefine(true, rdLibConflict, 1, "conflicting types for `Now`",
                                    RD_FILE ":1:5")) { correct++; }

    /* i64 vs u64 return differ (std.ktmh: `public u64 date.nowMs();`) */
    tests++;
    if (runRedefine(true,
"i64 NowMilliseconds() { return 0; }
void main() { }
", 1, "conflicting types for `NowMilliseconds`", null)) { correct++; }

    /* Two prototypes in the program that disagree */
    tests++;
    if (runRedefine(true,
"i64 Twice(i64 a);
i64 Twice(u8 *a);
void main() { }
", 1, "conflicting types for `Twice`: `i64 Twice(u8 *a);`", RD_FILE ":2:5")) { correct++; }

    /* A definition that disagrees with the program's prototype on a
     * parameter, on varargs, or the number of parameters */
    tests++;
    if (runRedefine(false,
"i64 Add(i64 a, i64 b);
i64 Add(i64 a, f64 b) { return a; }
void main() { }
", 1, "conflicting types for `Add`", RD_FILE ":2:5")) { correct++; }
    tests++;
    if (runRedefine(true,
"void Log(u8 *fmt, ...);
void Log(u8 *fmt) { }
void main() { }
", 1, "conflicting types for `Log`", null)) { correct++; }
    tests++;
    if (runRedefine(true,
"i64 One(i64 a);
i64 One(i64 a, i64 b) { return a + b; }
void main() { }
", 1, "conflicting types for `One`", null)) { correct++; }

    /* A prototype after the definition that disagrees */
    tests++;
    if (runRedefine(true,
"i64 Late() { return 1; }
f64 Late();
void main() { }
", 1, "conflicting types for `Late`", RD_FILE ":2:5")) { correct++; }

    /* Matching prototypes, repeated, with a default argument, an array
     * parameter for a pointer one and varargs, are all fine */
    tests++;
    if (runRedefine(false,
"struct Pt { i64 x; };
i64 sum(i64 *v, i64 n=2);
i64 sum(i64 *v, i64 n=2);
i64 sum(i64 v[], i64 n) { i64 s = 0, i; for (i = 0; i < n; i++) { s += v[i]; } return s; }
i64 sum(i64 *v, i64 n);
i64 getX(Pt *p);
i64 getX(Pt *p) { return p.x; }
void say(u8 *fmt, ...args);
void say(u8 *fmt, ...args) { printf(\"said %d\\n\", args.len); }
void main() {
  i64 v[2] = {3, 4};
  Pt p;
  p.x = 9;
  say(\"x\", 1, 2);
  printf(\"sum=%d x=%d\\n\", sum(v, 2), getX(&p));
}
", 0, "said 2", "sum=7 x=9")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
}
