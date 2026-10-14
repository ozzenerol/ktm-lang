import cstr, fs, libc, mem, proc, reflect, str, test;

/* `any ...args` (v0.20.0): extras of any type, each a reflect.Value of a
 * copy made at the call; arrays (string literals) go as slices. Spread
 * on, a method's, none at all, from file-scope code, from a module built
 * as a library, and the errors. */

#define DIR "./ktm-778"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) \"$K\" ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

struct P { i64 x, y; };

/* What each extra is: "i64=42 u8[]=text ..." */
String gGot;

void describe(any ...args) {
    gGot.free();
    for (i64 i = 0; i < args.len; i++) {
        reflect.Value v = args[i];
        if (i) { gGot.append(" "); }
        gGot.append(v.type.name);
        if (str.equals(v.type.name, "i64")) { gGot.appendFormat("=%d", *(i64 *)v.ptr); }
        else if (str.equals(v.type.name, "u8[]")) {
            u8[] s = *(u8[] *)v.ptr;
            gGot.appendFormat("=%.*s", s.len, s.ptr);
        } else if (str.equals(v.type.name, "f64")) { gGot.appendFormat("=%.1f", *(f64 *)v.ptr); }
        else if (str.equals(v.type.name, "bool")) { gGot.appendFormat("=%s", *(bool *)v.ptr ? "true" : "false"); }
        else if (str.equals(v.type.name, "String")) {
            String *s = v.ptr;
            gGot.appendFormat("=%.*s", s.len, s.ptr);
        } else if (str.equals(v.type.name, "P")) {
            P *p = v.ptr;
            gGot.appendFormat("=%d,%d", p.x, p.y);
        }
    }
}

i64 count(u8 *label, i64 base = 10, any ...args) { return base + args.len; }

void forward(any ...args) { describe(...args); }

struct Box { i64 n; };
i64 Box.add(any ...args) {
    for (i64 i = 0; i < args.len; i++) { self.n += *(i64 *)args[i].ptr; }
    return self.n;
}

/* the copy is made at the call: changing x after doesn't show */
void *gSeen;
i64 gSeenVal;
void keep(any ...args) { gSeen = args[0].ptr; gSeenVal = *(i64 *)args[0].ptr; }

i64 gAtStart = count("start", 1, 2, 3);

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
    printf("Test - any ...args: ");
    i64 n = 42;
    String s = str.from("hi");
    P p = {1, 2};
    describe(n, "text", 3.5, true, s, p, n + 1);
    check(str.equals(gGot[0:], "i64=42 u8[]=text f64=3.5 bool=true String=hi P=1,2 i64=43"), "the values and types");
    describe(&p, null);
    check(str.equals(gGot[0:], "P * void *"), "pointers");
    describe();
    check(gGot.len == 0, "no extras");
    forward(7, "x");
    check(str.equals(gGot[0:], "i64=7 u8[]=x"), "spread on to another any function");
    check(count("a") == 10 && count("a", 1) == 1 && count("a", 1, "x", 2) == 3, "after a default parameter");
    Box b = {1};
    check(b.add(2, 3) == 6, "a method");
    check(gAtStart == 3, "a call in file-scope code");
    i64 x = 5;
    keep(x);
    x = 6;
    check(gSeenVal == 5 && *(i64 *)gSeen == 5 && gSeen != &x, "a copy, not the variable");
    s.free();
    gGot.free();

    /* the errors, and a module with an any function built as a library */
    proc.shell("rm -rf " DIR " && mkdir -p " DIR "/lib/fmtx " DIR "/app");
    put(DIR "/e1.ktm", "void f(any ...a) {}\nvoid main() { f(1); }\n");
    prints("cd " DIR " && " KTM "e1.ktm 2>&1", "`any ...` needs `import reflect;`", "any without reflect");
    put(DIR "/e2.ktm", "import reflect, str;\nvoid f(any ...a) {}\nvoid main() { f(str.from(\"x\")); }\n");
    prints("cd " DIR " && " KTM "e2.ktm 2>&1", "a String from a call would never be freed", "a String from a call");
    put(DIR "/e3.ktm", "import reflect;\nvoid g() {}\nvoid f(any ...a) {}\nvoid main() { f(g()); }\n");
    prints("cd " DIR " && " KTM "e3.ktm 2>&1", "argument 1 of `f` has no value", "a void argument");
    put(DIR "/e4.ktm", "import reflect;\nvoid f(reflect.Value ...a) {}\nvoid main() {}\n");
    prints("cd " DIR " && " KTM "e4.ktm 2>&1", "(or `any ...a`)", "the hint for typed varargs");
    put(DIR "/lib/fmtx/fmtx.ktm",
"module fmtx;
import reflect;
pub i64 total(u8 *what, any ...args) {
    i64 t = 0;
    for (i64 i = 0; i < args.len; i++) {
        if (args[i].type.kind == reflect.Kind.int) { t += *(i64 *)args[i].ptr; }
    }
    return t;
}
");
    put(DIR "/app/main.ktm",
"import fmtx;
void main() { i64 k = 4; printf(\"total=%d\\n\", fmtx.total(\"x\", 1, k, \"skip\", 5)); }
");
    prints("cd " DIR "/lib && " KTM "-lib fmtx fmtx 2>&1 && cd ../app && KTM_PATH=../lib " KTM "main.ktm -o app 2>&1 && ./app",
           "total=10", "from a module built as a library");
    prints("cd " DIR "/app && KTM_PATH=../lib " KTM "-jit main.ktm 2>&1", "total=10", "the library in the JIT");
    proc.shell("rm -rf " DIR);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
