import fs, libc, mem, proc, str, test;

/* `i64 v = **pp(i64 *);` with `Pt **pp`: a cast is postfix, so this is
 * `**(pp(i64 *))`, and the second `*` dereferences an i64. -jit and AOT
 * report that; -transpile printed "Unary type is being assigned as null"
 * and segfaulted. It now reports the error like -jit (test 226). The
 * transpiler also wrote a cast unbracketed, so `p(i64 *)[1]` became
 * `(long *)p[1]` (a cast of `p[1]` in C). */

#define TC_FILE "__223_transpile.ktm"
#define TC_C    "__223_transpile.c"
#define TC_EXE  "__223_transpile.exe"
#define TC_OUT  "__223_transpile.out"
#define TC_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
#define TC_KTM TC_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

void cleanupTranspileCastTest(void) {
    proc.shell("rm -f " TC_FILE " " TC_C " " TC_EXE " " TC_OUT);
}

u8 *tcBad =
"struct Pt { i64 x; i64 y; };
void main()
{
  Pt pt;
  pt.x = 7;
  Pt *q = &pt;
  Pt **pp = &q;
  i64 v = **pp(i64 *);
  printf(\"%d\\n\", v);
}
";

u8 *tcGood =
"struct Pt { i64 x; i64 y; };
void main()
{
  Pt pt;
  pt.x = 7; pt.y = 9;
  Pt *q = &pt;
  Pt **pp = &q;
  i64 v = *(i64 *)(*pp);
  i64 w = ((i64 *)(*pp))[1];
  i64 a = 3, b = 4;
  f64 f = (f64)(a + b) / 2;
  i64 n = -(f64)a * 2;
  u8 *bytes = (u8 *)q;
  i64 z = *(i64 *)(bytes + 8);
  printf(\"%d %d %.1f %d %d\\n\", v, w, f, n, z);
}
";

struct Pt { i64 x; i64 y; };

void writeSource(u8 *src) {
    cleanupTranspileCastTest();
    fs.writeAll(TC_FILE, src, str.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
}

void main() {
    printf("Test - transpile cast after a dereference: ");
    i64 correct = 0, tests = 0, res;
    u8 *out;

    /* The same expressions compiled here (AOT or JIT) */
    tests++;
    Pt pt;
    pt.x = 7; pt.y = 9;
    Pt *q = &pt;
    Pt **pp = &q;
    i64 a = 3, b = 4;
    u8 *bytes = (u8 *)q;
    if (*(i64 *)(*pp) == 7 && ((i64 *)(*pp))[1] == 9 &&
            (f64)(a + b) / 2 == 3.5 && -(f64)a * 2 == -6.0 &&
            *(i64 *)(bytes + 8) == 9) { correct++; }

    /* The wrong program is an error under -jit... */
    writeSource(tcBad);
    tests++;
    res = proc.shell(TC_KTM "-jit " TC_FILE " > " TC_OUT " 2>&1");
    out = fs.readAll(TC_OUT);
    if (res == 1 && out && libc.strstr(out, "`*` on `i64` is invalid")) { correct++; }
    else { printf("jit: %d %s\n", res, out); }
    mem.free(out);

    /* ...and -transpile no longer crashes on it: it reports the same
     * error (test 226) */
    tests++;
    res = proc.shell(TC_KTM "-transpile " TC_FILE " > " TC_C " 2> " TC_OUT);
    out = fs.readAll(TC_OUT);
    u8 *code = fs.readAll(TC_C);
    if (res == 1 && out && !libc.strstr(out, "NULL") &&
            libc.strstr(out, "`*` on `i64` is invalid")) { correct++; }
    else { printf("transpile: %d %s\n", res, out); }
    mem.free(out);
    mem.free(code);

    /* The valid program transpiles to C that brackets the casts, compiles
     * and prints what the JIT does (only with a C compiler on the PATH) */
    writeSource(tcGood);
    tests++;
    res = proc.shell(TC_KTM "-transpile " TC_FILE " > " TC_C " 2>/dev/null");
    code = fs.readAll(TC_C);
    if (res == 0 && code && libc.strstr(code, "long w = ((long *)*pp)[1];") &&
            libc.strstr(code, "((double)(a + b)) / 2") &&
            libc.strstr(code, "*((long *)(bytes + 8))")) { correct++; }
    else { printf("%s\n", code); }
    mem.free(code);

    tests++;
    res = proc.shell("command -v cc >/dev/null 2>&1");
    if (res != 0) {
        correct++;
    } else {
        res = proc.shell("cc -w -o " TC_EXE " " TC_C " -lm > " TC_OUT " 2>&1 && " TC_TIMEOUT "./" TC_EXE " > " TC_OUT " 2>&1");
        out = fs.readAll(TC_OUT);
        if (res == 0 && out && !str.cmp(out, "7 9 3.5 -6 9\n")) { correct++; }
        else { printf("cc: %s\n", out); }
        mem.free(out);
    }

    cleanupTranspileCastTest();
    test.printResult(correct, tests);
    printf("====\n");
}
