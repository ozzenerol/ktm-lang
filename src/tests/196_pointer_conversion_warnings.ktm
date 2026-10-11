import fs, libc, mem, proc, str, test;

/* `i64 *q; q -= q;` (a pointer difference, an integer, stored back into
 * the pointer) and `f64 f; i64 *q = &f;` (incompatible pointer types)
 * compiled with no diagnostic. Both now warn, as C does, at the
 * operator; so do `q = &f`, passing `&f` for an `i64 *` parameter and
 * returning an `f64 *` as an `i64 *`. They stay warnings: the program
 * still builds and runs. `void *`, byte pointers, same types, `q += 1`,
 * `i64 d = q - q` and explicit casts don't warn. */

#define PTRCONV_FILE "__196_ptrconv.ktm"
#define PTRCONV_OUT  "__196_ptrconv.out"
#define PTRCONV_RUN  "__196_ptrconv.run"

void cleanupPtrConvTest(void) {
    fs.remove(PTRCONV_FILE);
    fs.remove(PTRCONV_OUT);
    fs.remove(PTRCONV_RUN);
    fs.remove("./a.out");
}

/* Compile `source` with ktm; returns the compiler output (null if the
 * compile failed) and leaves ./a.out */
u8 *compileOutput(u8 *source) {
    i64 res;

    cleanupPtrConvTest();
    if (!fs.writeAll(PTRCONV_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return null;
    }
    res = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                             "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                             PTRCONV_FILE " > " PTRCONV_OUT " 2>&1");
    if (res != 0) {
        return null;
    }
    return fs.readAll(PTRCONV_OUT);
}

/* Run ./a.out and check what it prints */
bool runPrints(u8 *expected) {
    u8 *out;
    bool ok = false;
    if (proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                         "./a.out > " PTRCONV_RUN " 2>&1") != 0) {
        return false;
    }
    out = fs.readAll(PTRCONV_RUN);
    if (out) {
        ok = str.cmp(out, expected) == 0;
        if (!ok) { printf("got: %s\n", out); }
        mem.free(out);
    }
    return ok;
}

bool has(u8 *out, u8 *needle) {
    if (out && libc.strstr(out, needle)) { return true; }
    if (out) { printf("missing `%s` in:\n%s\n", needle, out); }
    return false;
}

i32 main() {
    i64 correct = 0, tests = 12;
    u8 *out;

    printf("Test - pointer conversion warnings: ");

    out = compileOutput(
"void takeI(i64 *p) { if (p) { printf(\"take\\n\"); } }
i64 *retI(f64 *f) { return f; }
void main() {
  f64 f = 1.5;
  i64 x = 3;
  i64 *q = &x;
  i64 *r = &f;
  r = &f;
  takeI(&f);
  printf(\"%d\\n\", retI(&f) == &f);
  q -= q;
  printf(\"%d %d\\n\", q == null, *r == *(i64 *)(&f));
}
");
    if (out) { correct++; }
    if (has(out, "Incompatible types 'f64 *' is not assignable to type 'i64 *'\n --> "
                             PTRCONV_FILE ":7:10")) { correct++; }
    if (has(out, "Incompatible types 'f64 *' is not assignable to type 'i64 *'\n --> "
                             PTRCONV_FILE ":8:5")) { correct++; }
    if (has(out, "Incompatible function argument, expected 'i64 *' got 'f64 *' in function 'takeI'\n --> "
                             PTRCONV_FILE ":9:")) { correct++; }
    if (has(out, "unexpected return value 'f' of type 'f64 *' expected 'i64 *'")) { correct++; }
    if (has(out, "Incompatible types 'u64' is not assignable to type 'i64 *'\n --> "
                             PTRCONV_FILE ":11:5")) { correct++; }
    if (out && !libc.strstr(out, "error")) { correct++; }
    /* Still a working program */
    if (out && runPrints("take\n1\n1 1\n")) { correct++; }
    mem.free(out);

    /* None of these warn */
    out = compileOutput(
"struct C { i64 a; };
void takeU(void *p) { if (!p) { printf(\"null\\n\"); } }
void main() {
  f64 f = 1.5;
  i64 x = 3, d;
  i64 *q = &x;
  void *v = &f;
  i64 *z = v;
  v = q;
  q = &x;
  d = q - q;
  q += 1; q -= 1; q++; q--;
  q = (i64 *)(&f);
  q = &x;
  u8 *s = \"abc\";
  i8 *t = s;
  s = t;
  u8 **pp = &s;
  u64 *w = s + 1;
  s = w;
  bool *b = s;
  f64 *fp = &f;
  f64 *fq = fp;
  C c; C *cp = &c;
  takeU(&f);
  takeU(q);
  i64 *n = null;
  n = 0;
  printf(\"%d %d %d\\n\", d, *q, n == null);
}
");
    if (out) { correct++; }
    if (out && !libc.strstr(out, "warning")) { correct++; }
    else if (out) { printf("%s\n", out); }
    if (out && runPrints("0 3 1\n")) { correct++; }
    mem.free(out);

    /* `i64 *q = &f` alone, from a global */
    out = compileOutput(
"f64 g = 2.0;
void main() {
  i64 *q = &g;
  printf(\"%d\\n\", q != null);
}
");
    if (has(out, "Incompatible types 'f64 *' is not assignable to type 'i64 *'\n --> "
                             PTRCONV_FILE ":3:10")) { correct++; }
    mem.free(out);

    cleanupPtrConvTest();
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
