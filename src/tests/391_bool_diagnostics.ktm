#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* bool converts like C's _Bool (any non-zero value is 1), but the
 * diagnostics hadn't caught up: a pointer converted to bool warned
 * "Incompatible types 'u8 *' is not assignable to type 'i8'", where C
 * doesn't warn, and every message spelled bool as 'i8'. Pointer and
 * function to bool is now silent (initialiser, assignment, class field,
 * argument, return), pointer to a narrower integer still warns, and
 * messages and typeof say bool. */

#define DIAG_SRC "./ktm-391-bool.ktm"
#define DIAG_OUT "./ktm-391-bool.out"

/* Compile `source` and return what ktm printed */
u8 *diagnose(u8 *source) {
    proc.shell("rm -f " DIAG_SRC " " DIAG_OUT);
    fs.writeAll(DIAG_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " DIAG_SRC " -o /dev/null > " DIAG_OUT " 2>&1");
    u8 *out = fs.readAll(DIAG_OUT);
    proc.shell("rm -f " DIAG_SRC " " DIAG_OUT);
    return out;
}

i64 count(u8 *s, u8 *what) {
    i64 n = 0;
    while (s && (s = libc.strstr(s, what))) {
        n++;
        s++;
    }
    return n;
}

/* ktm prints `warnings` warnings, no error, and each of want1/want2 */
bool prints(u8 *source, i64 warnings, u8 *want1, u8 *want2=null) {
    u8 *out = diagnose(source);
    bool ok = out && count(out, "warning:") == warnings &&
                        count(out, "error:") == 0 &&
                        (!want1 || libc.strstr(out, want1)) && (!want2 || libc.strstr(out, want2));
    if (!ok) { printf("\n%s\n", out); }
    mem.free(out);
    return ok;
}

i64 gCalled;
void Fn() { gCalled++; }
bool asBool(bool b) { return b; }
bool ptrToBool(u8 *p) { return p; }

i32 main() {
    printf("Test - bool diagnostics: ");
    i64 correct = 0, tests = 0;
    u8 *out;

    /* pointer, array and function to bool: no warning anywhere */
    tests++;
    if (prints(
"struct C { bool f; };
void F() {}
void take(bool b) {}
bool ret(u8 *p) { return p; }
bool retFn() { return &F; }
void main()
{
  u8 *p = \"x\", buf[4];
  bool a = p, b = buf, f = &F;
  C c;
  a = p;
  c.f = p;
  take(p);
  take(&F);
}
", 0, null)) { correct++; }

    /* other pointer to integer conversions still warn */
    tests++;
    if (prints(
"i32 ret(u8 *p) { return p; }
void take(u8 b) {}
void main()
{
  u8 *p = \"x\";
  i32 i = p;
  u8 u = p;
  i8 s = p;
  take(p);
}
", 5, "'u8 *' is not assignable to type 'i32'",
            "expected 'u8' got 'u8 *'")) { correct++; }

    /* messages spell bool as bool (a class passed for a bool is an
     * error since argument types are checked like C) */
    tests++;
    out = diagnose(
"struct C { i64 a; };
void Take(bool b) {}
bool Ret() { C c; return c; }
void main()
{
  C c;
  Take(c);
}
");
    if (out && count(out, "warning:") == 1 && count(out, "error:") == 1 &&
            libc.strstr(out, "bool Ret(void); unexpected return value 'c' of type 'C' expected 'bool'") &&
            libc.strstr(out, "error: Incompatible function argument, expected 'bool' got 'C' in function 'Take'")) {
        correct++;
    } else {
        printf("\n%s\n", out);
    }
    mem.free(out);

    tests++;
    out = diagnose(
"struct C { i64 a; };
void main()
{
  C c;
  bool b = c;
}
");
    if (out && libc.strstr(out, "`bool = C` is invalid") &&
            libc.strstr(out, "applied to a bool type")) { correct++; }
    else { printf("\n%s\n", out); }
    mem.free(out);

    /* typeof names the type the same way */
    bool bb;
    tests++;
    if (!str.cmp(typeof(bb), "bool") && !str.cmp(typeof(&bb), "bool *")) { correct++; }
    else { printf("typeof %s / %s ", typeof(bb), typeof(&bb)); }

    /* and the conversions give 0 or 1 */
    u8 *p = "x", *np = null;
    bool t = p, n = np, f = &Fn;
    tests++;
    if (t == 1 && n == 0 && f == 1 && asBool(p) == 1 && asBool(np) == 0 &&
            ptrToBool(p) == 1 && ptrToBool(null) == 0) { correct++; }
    else { printf("values %d %d %d ", t, n, f); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
