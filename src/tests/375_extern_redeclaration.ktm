import cstr, fs, libc, mem, proc, str, test;

/* `i64 Exit(i64 x)` in a program silently replaced the library's
 * `_extern` bound Exit on x86_64 and was rejected on arm64. With the
 * library in modules a program's exit, alloc and toLower are its own
 * functions on every architecture, next to os.exit, mem.alloc and
 * str.toLower (asm bindings included). */

#define REDECL_FILE "__375_redecl.ktm"
#define REDECL_OUT  "__375_redecl.out"
#define REDECL_MSG  "conflicting types for `"

void cleanupRedecl(void) {
    fs.remove(REDECL_FILE);
    fs.remove(REDECL_OUT);
}

/* Compiles and runs `src` with the JIT. With `name` set, true when the
 * compile fails with the conflicting-types error for it; with null, true
 * when it compiles, runs and prints "ok" */
bool conflicts(u8 *src, u8 *name) {
    u8 *out, want[64];
    i64 res;
    bool ok;

    cleanupRedecl();
    if (!fs.writeAll(REDECL_FILE, cstr.view(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    res = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " REDECL_FILE " > " REDECL_OUT " 2>&1");
    out = fs.readAll(REDECL_OUT).take();
    if (name) {
        cstr.formatTo(want, REDECL_MSG "%s`", name);
        ok = res != 0 && out && libc.strstr(out, want) != null;
    } else {
        ok = res == 0 && out && !cstr.cmp(out, "ok\n");
    }
    if (!ok && out) { printf("%s\n", out); }
    mem.free(out);
    cleanupRedecl();
    return ok;
}

i32 main() {
    printf("Test - extern redeclaration: ");
    i64 correct = 0, tests = 0;

    /* a program's exit, alloc and toLower are its own: os.exit, mem.alloc
     * and str.toLower (asm bindings on some architectures) are untouched */
    tests++;
    if (conflicts(
"import mem, str;

i64 exit(i64 x) { return x * 2; }
i64 alloc(i64 n) { return n + 1; }
i64 toLower(i64 ch) { return 0; }
void main()
{
  u8 *p = mem.alloc(4);
  if (exit(21) == 42 && alloc(3) == 4 && toLower('A') == 0 && str.toLower('A') == 'a') { printf(\"ok\\n\"); }
  mem.free(p);
}
", null)) { correct++; }

    /* and the library's own functions work as before */
    tests++;
    if (conflicts(
"import math, mem, os, str;

void main()
{
  u8 *p = mem.alloc(4);
  if (p && str.toUpper('o') == 'O' && str.isSpace(' ') && math.abs(-3) == 3) { printf(\"ok\\n\"); }
  mem.free(p);
  os.exit(0);
}
", null)) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
