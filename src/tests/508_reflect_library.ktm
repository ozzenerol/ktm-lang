import cstr, fs, libc, mem, proc, test;

/* v0.4.0 R1: a module that asks for a type's description, compiled from
 * source and as a prebuilt -lib library (its descriptions are filled in by
 * the library's _init). The program and the library each have their own
 * copy, so they differ by address and agree by reflect.same. */

#define DIR "./ktm-508"
/* $K is ../../ktm as an absolute path (the commands cd first) */
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) \"$K\" ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

void put(u8 *path, u8 *text) {
    fs.writeAll(cstr.view(path), cstr.view(text));
}

bool prints(u8 *cmd, u8 *want, u8 *what) {
    u8 *out = proc.shellOutput("K=\"$(pwd)/../../ktm\"; %s", cmd).take();
    bool ok = out && libc.strstr(out, want) != null;
    check(ok, what);
    if (!ok) { printf("  got: %s\n", out); }
    mem.free(out);
    return ok;
}

i32 main() {
    printf("Test - reflect in a library: ");
    proc.shell("rm -rf " DIR " && mkdir -p " DIR "/pre/inv " DIR "/app");
    put(DIR "/pre/inv/inv.ktm",
"module inv;
import reflect;
pub struct Item { i64 id; u8[] title; private i64 cost; };
pub reflect.Type *itemType() { return reflect.typeOf(Item); }
");
    put(DIR "/app/main.ktm",
"import inv, reflect;
void main()
{
    reflect.Type *a = inv.itemType();
    reflect.Type *b = reflect.typeOf(inv.Item);
    printf(\"%s %d %s private=%d same=%d\\n\", a.name, a.fields.len, a.fields[1].name,
           a.fields[2].isPrivate, reflect.same(a, b));
}
");
    u8 *want = "inv.Item 3 title private=1 same=1";
    prints("cd " DIR "/app && KTM_PATH=../pre " KTM "-jit main.ktm 2>&1", want, "a source module, JIT");
    prints("cd " DIR "/app && KTM_PATH=../pre " KTM "main.ktm -o bin 2>&1 && ./bin", want,
           "a source module, compiled");
    prints(KTM "-lib inv " DIR "/pre/inv 2>&1; cat " DIR "/pre/inv/.ktmlib", "init",
           "the library has an _init");
    prints("cd " DIR "/app && KTM_PATH=../pre " KTM "main.ktm -o bin2 2>&1 && ./bin2", want,
           "a prebuilt library, compiled");
    prints("cd " DIR "/app && KTM_PATH=../pre " KTM "-jit main.ktm 2>&1", want, "a prebuilt library, JIT");
    proc.shell("rm -rf " DIR);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
