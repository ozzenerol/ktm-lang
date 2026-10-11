#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* -transpile reported no errors: a bad statement was dropped from the C
 * without a word, and `i64 v = ;` segfaulted (so did -jit). The program
 * is now checked by the compiler's front end first; its diagnostics are
 * printed as for -jit and an error stops the transpile. */

#define TD_FILE "__226_transpile.ktm"
#define TD_C    "__226_transpile.c"
#define TD_ERR  "__226_transpile.err"
#define TD_JIT  "__226_transpile.jit"
#define TD_EXE  "__226_transpile.exe"
#define TD_OUT  "__226_transpile.out"
#define TD_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
#define TD_KTM TD_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

void cleanupTranspileDiagTest(void) {
    proc.shell("rm -f " TD_FILE " " TD_C " " TD_ERR " " TD_JIT " " TD_EXE " " TD_OUT);
}

/* -transpile fails with status 1 (no crash), prints exactly what -jit
 * prints (which contains `want`) and writes no C */
bool failsLikeJit(u8 *source, u8 *want) {
    i64 res, jitRes;
    u8 *err, *jit, *code;
    bool ok;
    cleanupTranspileDiagTest();
    fs.writeAll(TD_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    res = proc.shell(TD_KTM "-transpile " TD_FILE " > " TD_C " 2> " TD_ERR);
    jitRes = proc.shell(TD_KTM "-jit " TD_FILE " > /dev/null 2> " TD_JIT);
    err = fs.readAll(TD_ERR);
    jit = fs.readAll(TD_JIT);
    code = fs.readAll(TD_C);
    ok = res == 1 && jitRes == 1 && err && jit && !str.cmp(err, jit) &&
             libc.strstr(err, want) && (!code || str.len(code) == 0);
    if (!ok) { printf("transpile %d jit %d:\n%s\n--- jit:\n%s\n", res, jitRes, err, jit); }
    mem.free(err);
    mem.free(jit);
    mem.free(code);
    return ok;
}

u8 *tdGood =
"#define GREETING \"hi\"
struct Pt { i64 x; i64 y; };
i64 sum(Pt *p) { return p.x + p.y; }
void main()
{
  Pt pt;
  pt.x = 3;
  pt.y = 4;
  i64 z = 1;
  f64 f = z;
  printf(\"%s %d %.1f\\n\", GREETING, sum(&pt), f);
}
";

/* Valid, with macros pasted into strings, which the transpile parse
 * leaves unexpanded */
u8 *tdPaste =
"#define M \"mid\"
const i64 N = 3;
void main()
{
  i64 x = N;
  printf(\"a\" M \"b %d\\n\", x);
}
";

void main() {
    printf("Test - transpile reports errors: ");
    i64 correct = 0, tests = 0, res;
    u8 *out, *err;

    tests++;
    if (failsLikeJit("void main() { i64 v = ; }\n",
                                     "__226_transpile.ktm:1:19\n")) { correct++; }
    tests++;
    if (failsLikeJit("void main()\n{\n  i64 a = 1;\n  b = 2;\n}\n",
                                     "Variable or function `b` has not been defined")) { correct++; }
    tests++;
    if (failsLikeJit("struct P { i64 x, y; };\nvoid main()\n{\n  P p;\n  i64 z = p;\n}\n",
                                     "`i64 = P` is invalid")) { correct++; }
    tests++;
    if (failsLikeJit("struct Pt { i64 x; };\nvoid main()\n{\n  Pt pt;\n  Pt *q = &pt;\n"
                                     "  Pt **pp = &q;\n  i64 v = **(i64 *)pp;\n}\n",
                                     "`*` on `i64` is invalid")) { correct++; }
    tests++;
    if (failsLikeJit("void main()\n{\n  i64 x = (1 + 2;\n}\n", "error:")) { correct++; }

    /* A valid program still transpiles, with no diagnostics, and its C
     * prints what the JIT does (only with a C compiler on the PATH) */
    cleanupTranspileDiagTest();
    fs.writeAll(TD_FILE, tdGood, str.len(tdGood), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    tests++;
    res = proc.shell(TD_KTM "-transpile " TD_FILE " > " TD_C " 2> " TD_ERR);
    err = fs.readAll(TD_ERR);
    if (res == 0 && err && str.len(err) == 0) { correct++; }
    else { printf("good: %d %s\n", res, err); }
    mem.free(err);
    tests++;
    if (proc.shell("command -v cc >/dev/null 2>&1") != 0) {
        correct++;
    } else {
        res = proc.shell("cc -w -o " TD_EXE " " TD_C " -lm > " TD_OUT " 2>&1 && " TD_TIMEOUT "./" TD_EXE " > " TD_OUT " 2>&1");
        out = fs.readAll(TD_OUT);
        if (res == 0 && out && !str.cmp(out, "hi 7 1.0\n")) { correct++; }
        else { printf("cc: %s\n", out); }
        mem.free(out);
    }

    /* Macros pasted into strings are no error */
    cleanupTranspileDiagTest();
    fs.writeAll(TD_FILE, tdPaste, str.len(tdPaste), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    tests++;
    res = proc.shell(TD_KTM "-transpile " TD_FILE " > " TD_C " 2> " TD_ERR);
    err = fs.readAll(TD_ERR);
    if (res == 0 && err && !libc.strstr(err, "error")) { correct++; }
    else { printf("paste: %d %s\n", res, err); }
    mem.free(err);

    cleanupTranspileDiagTest();
    PrintResult(correct, tests);
    printf("====\n");
}
