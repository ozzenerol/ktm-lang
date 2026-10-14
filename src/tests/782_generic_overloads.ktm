import cstr, fs, libc, mem, proc, test;

/* Generic functions overloaded on their parameters (v0.20.0): the call
 * picks the definition its arguments fit, with type arguments written
 * or worked out. Before, the last definition always won, with only a
 * warning. In a module, and from a module built as a library. */

#define DIR "./ktm-782"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) \"$K\" ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

struct A { i64 a; };
struct B { i64 b; };

u8 *which<T>(A *x) { return "A"; }
u8 *which<T>(B *x) { return "B"; }

u8 *kind<T>(A *x, T v) { return "A"; }
u8 *kind<T>(B *x, T v) { return "B"; }

T first<T>(T[] xs) { return xs[0]; }
T first<T>(T *p, i64 n) { return p[n - 1]; }

void put(u8 *path, u8 *text) { fs.writeAll(cstr.view(path), cstr.view(text)); }

bool prints(u8 *cmd, u8 *want, u8 *what) {
    u8 *out = proc.shellOutput("K=\"$(pwd)/../../ktm\"; %s", cmd).take();
    bool ok = out && libc.strstr(out, want) != null;
    check(ok, what);
    if (!ok) { printf("  got: %s\n", out); }
    mem.free(out);
    return ok;
}

i32 main() {
    printf("Test - generic overloads: ");
    A a;
    B b;
    check(*which<i64>(&a) == 'A' && *which<i64>(&b) == 'B', "written type arguments pick by the pointer");
    check(*which<f64>(&b) == 'B' && *which<f64>(&a) == 'A', "another instance, the other order");
    check(*kind(&a, 1) == 'A' && *kind(&b, 2.5) == 'B' && *kind(&a, "x") == 'A', "worked out type arguments");
    i64 arr[3] = {7, 8, 9};
    check(first(arr[0:3]) == 7 && first(&arr[0], 2) == 8, "different parameter counts");

    proc.shell("rm -rf " DIR " && mkdir -p " DIR "/lib/pick " DIR "/app");
    put(DIR "/lib/pick/pick.ktm",
"module pick;
pub struct Conn { i64 n; };
pub struct Pool { i64 n; };
pub u8 *on<T>(Conn *c) { return \"conn\"; }
pub u8 *on<T>(Pool *p) { return \"pool\"; }
");
    put(DIR "/app/main.ktm",
"import pick;
void main() { pick.Conn c; pick.Pool p; printf(\"%s %s\\n\", pick.on<i64>(&c), pick.on<i64>(&p)); }
");
    prints("cd " DIR "/app && KTM_PATH=../lib " KTM "-jit main.ktm 2>&1", "conn pool", "in a module");
    prints("cd " DIR "/lib && " KTM "-lib pick pick 2>&1 && cd ../app && KTM_PATH=../lib " KTM "main.ktm -o app 2>&1 && ./app",
           "conn pool", "from a library");
    put(DIR "/e1.ktm",
"struct A { i64 a; };\nstruct B { i64 b; };\nstruct C { i64 c; };\n"
"u8 *w<T>(A *x) { return \"A\"; }\nu8 *w<T>(B *x) { return \"B\"; }\n"
"void main() { C c; w<i64>(&c); }\n");
    prints("cd " DIR " && " KTM "e1.ktm 2>&1", "error", "no definition fits: an error, not a warning");
    proc.shell("rm -rf " DIR);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
