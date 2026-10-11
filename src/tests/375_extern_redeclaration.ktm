#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* A definition or prototype that doesn't match the standard library's
 * declaration is an error ("conflicting types"), but the check didn't
 * see `_extern _LABEL T Name(...)` bindings: on x86_64 `i64 Exit(i64 x)`
 * silently replaced the library's `void Exit(i64)`, while on arm64 (where
 * Exit is a plain prototype) the same file was rejected. Now both give
 * the same error. */

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
    if (!fs.writeAll(REDECL_FILE, src, str.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    res = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " REDECL_FILE " > " REDECL_OUT " 2>&1");
    out = fs.readAll(REDECL_OUT);
    if (name) {
        str.formatTo(want, REDECL_MSG "%s`", name);
        ok = res != 0 && out && libc.strstr(out, want) != null;
    } else {
        ok = res == 0 && out && !str.cmp(out, "ok\n");
    }
    if (!ok && out) { printf("%s\n", out); }
    mem.free(out);
    cleanupRedecl();
    return ok;
}

i32 main() {
    printf("Test - extern redeclaration: ");
    i64 correct = 0, tests = 0;

    /* the repro: _extern on x86_64, a prototype on arm64 */
    tests++;
    if (conflicts(
"i64 Exit(i64 x) { return x * 2; }
void main() { printf(\"%d\\n\", Exit(21)); }
", "Exit")) { correct++; }

    /* _extern on every architecture */
    tests++;
    if (conflicts(
"i64 MAlloc(i64 n) { return n; }
void main() { MAlloc(3); }
", "MAlloc")) { correct++; }

    /* a prototype alone conflicts too */
    tests++;
    if (conflicts(
"i64 ToLower(i64 ch);
void main() { }
", "ToLower")) { correct++; }

    /* the same signature again is fine */
    tests++;
    if (conflicts(
"void *MAlloc(u64 size);
u8 ToUpper(u8 ch);
void main() { printf(\"ok\\n\"); }
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

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
