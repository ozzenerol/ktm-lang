import cstr, fs, libc, mem, proc, test;

/* A program may define functions named like the standard library's (an
 * AOT build once failed to link with "multiple definition of `Now'"):
 * with the library in modules its names are date.now, cstr.len, so the
 * program's own now() and len() are separate functions, in both modes.
 * A definition or prototype with the parameter types of an earlier one
 * and another return type is an error at its name; other parameter types
 * make an overload (v0.6.0, test 550). */

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
    if (!fs.writeAll(RD_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    if (jit) {
        res = proc.shell(RD_KTM "-jit " RD_FILE " > " RD_OUT " 2>&1");
    } else {
        res = proc.shell(RD_KTM RD_FILE " -o " RD_EXE " > " RD_OUT " 2>&1 && " RD_TIMEOUT "./" RD_EXE " >> " RD_OUT " 2>&1");
    }
    out = fs.readAll(RD_OUT).take();
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

/* The program's own now() and len() next to date.now() and cstr.len() */
u8 *rdOwnNames =
"import cstr, date;

i64 now() { return 42; }
i64 len(u8 *s) { return 7; }

void main()
{
  date.Date d = date.now();
  printf(\"now=%d len=%d cstr.len=%d real=%d\\n\", now(), len(\"abc\"), cstr.len(\"abc\"), d > 0);
}
";

void main() {
    printf("Test - redefine a library function: ");
    i64 correct = 0, tests = 0;

    /* Its own functions named like library ones: both are there, AOT
     * (they link: a module's names have symbols of their own) and JIT */
    tests++;
    if (runRedefine(false, rdOwnNames, 0, "now=42 len=7 cstr.len=3 real=1", null)) { correct++; }
    tests++;
    if (runRedefine(true, rdOwnNames, 0, "now=42 len=7 cstr.len=3 real=1", null)) { correct++; }

    /* Two prototypes with other parameter types: two overloads */
    tests++;
    if (runRedefine(true,
"i64 Twice(i64 a);
i64 Twice(u8 *a);
void main() { }
", 0, null, null)) { correct++; }

    /* A definition with other parameters than the program's prototype
     * is another overload; an old unnamed `...` is an error */
    tests++;
    if (runRedefine(false,
"i64 Add(i64 a, i64 b);
i64 Add(i64 a, f64 b) { return a; }
void main() { }
", 0, null, null)) { correct++; }
    tests++;
    if (runRedefine(true,
"void Log(u8 *fmt, ...);
void Log(u8 *fmt) { }
void main() { }
", 1, "`...` needs a name", null)) { correct++; }
    tests++;
    if (runRedefine(true,
"i64 One(i64 a);
i64 One(i64 a, i64 b) { return a + b; }
void main() { }
", 0, null, null)) { correct++; }

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
