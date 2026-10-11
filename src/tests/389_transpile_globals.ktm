import fs, libc, mem, proc, str, test;

/* `-transpile` dropped every file-scope global, so the C used undeclared
 * variables, and it dropped code at file scope (non-constant initialisers,
 * statements). Globals are now written with their type, linkage and
 * constant initialiser; file-scope code goes into __ktm_init(), which
 * main() runs first (a main() is generated when there is no Main, and the
 * user's Main becomes __ktm_Main when file-scope code calls it). Also
 * `&Fn` was written as `&&fn`. Each program here is transpiled, built
 * with cc and run. */

#define TG_FILE "__389_tg.ktm"
#define TG_C    "__389_tg.c"
#define TG_EXE  "__389_tg.exe"
#define TG_OUT  "__389_tg.out"
#define TG_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "

i64 gCorrect = 0, gTests = 0;

void cleanupTg() {
    proc.shell("rm -f " TG_FILE " " TG_C " " TG_EXE " " TG_OUT);
}

/* Transpile `src`, build the C and run it with "one": its output must be
 * `want` */
void transpilesTo(u8 *src, u8 *want, u8 *what) {
    u8 *out = null;
    gTests++;
    cleanupTg();
    fs.writeAll(TG_FILE, src, str.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    if (proc.shell(TG_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -transpile " TG_FILE " > " TG_C " 2>/dev/null") == 0 &&
            proc.shell("cc -w -o " TG_EXE " " TG_C " -lm > " TG_OUT " 2>&1 && " TG_TIMEOUT "./" TG_EXE " one > " TG_OUT " 2>&1") == 0) {
        out = fs.readAll(TG_OUT);
    }
    if (out && !str.cmp(out, want)) {
        gCorrect++;
    } else {
        printf("\n  failed: %s\n", what);
        if (out) { printf("  got: %s", out); }
        proc.shell("cat " TG_OUT " 2>/dev/null | head -5");
    }
    mem.free(out);
    cleanupTg();
}

i32 main() {
    printf("Test - transpile globals: ");
    if (proc.shell("command -v cc >/dev/null 2>&1") != 0) {
        /* no C compiler here: nothing to check */
        test.printResult(1, 1);
        printf("====\n");
        return 0;
    }

    transpilesTo(
"struct Pt { i64 x, y; };
i64 a;
i64 b = 5;
static i32 c = -1;
pub f64 d = 1.5;
u8 *s = \"hi\";
u8 buf[8] = \"abc\";
i64 arr[3] = {1, 2, 3};
Pt origin = {4, 5};
Pt *pp;
i64 e;
i32 main(i32 argc, u8 **argv)
{
  e = arr[2] + 1;
  printf(\"top %d\\n\", e);
  a = 7;
  printf(\"%d %d %d %.1f %s %s %d %d %d %d %d %s\\n\", a, b, c, d, s, buf, arr[2],
    origin.y, pp == null, e, argc, argv[1]);
  return 0;
}
",
"top 4\n7 5 -1 1.5 hi abc 3 5 1 4 2 one\n",
        "every kind of global, file-scope code before Main");

    transpilesTo(
"i64 twice(i64 v) { return v * 2; }
i64 x;

void main()
{
  x = twice(4);
  printf(\"script %d\\n\", x);
}
",
"script 8\n", "no Main: a main() runs the file-scope code");

    transpilesTo(
"i64 n = 3;
void runMain() { printf(\"main %d\\n\", n); }

void main()
{
  printf(\"before\\n\");
  runMain();
  printf(\"after\\n\");
}
",
"before\nmain 3\nafter\n", "file-scope code that calls Main runs it once");

    transpilesTo(
"i64 twice(i64 v) { return v * 2; }
fn(i64) i64 fp = &twice;
auto z = 6;
bool flag = 5;
i64 m = 1, k;
struct Pt { i64 x, y; };
Pt q = {m, 2};
u8 *np = null;
void main() {
  k = twice(m + 1); printf(\"%d %d %d %d %d %d %d\\n\", fp(5), z, flag, m, k, q.x + q.y, np == null); }
",
"10 6 1 1 4 3 1\n",
        "function pointer, auto, bool, a list, a {...} with a variable item");

    transpilesTo(
"i64 twice(i64 v) { return v * 2; }
void main() { fn(i64) i64 f = &twice; printf(\"%d\\n\", f(21)); }
",
"42\n", "&Fn in a function");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
