import fs, libc, mem, proc, test;

/* v0.24.0: on macOS a caught throw left the thread's signal mask garbled
 * (SIGSEGV among the blocked signals). A throw is a _longjmp, but on
 * Mach-O `_longjmp` named C's longjmp, which sets the mask from what the
 * try's _setjmp never saved. A thread started afterwards, or a program
 * run by proc.shell, inherited the mask, and a crash in it then spun
 * forever instead of being reported. Linux was right all along. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

/* the calling thread's signal mask (a null set only reads it) */
void maskOf(i64 *out) {
    for (i64 i = 0; i < 16; i++) { out[i] = 0; }
    libc.pthread_sigmask(0, null, out);
}

bool sameMask(i64 *a, i64 *b) {
    for (i64 i = 0; i < 16; i++) {
        if (a[i] != b[i]) { return false; }
    }
    return true;
}

enum Err { bad };

void fails() { throw(Err.bad, "bad"); }

void deeper(i64 n) {
    defer { n = 0; }
    if (n == 0) { fails(); }
    deeper(n - 1);
}

#define TM_SRC "./ktm-901-crash.ktm"
#define TM_ERR "./ktm-901-crash.err"

i32 main() {
    printf("Test - a throw keeps the signal mask: ");
    i64 before[16], after[16];
    maskOf(before);

    try { fails(); } catch (Error e) {}
    maskOf(after);
    check(sameMask(before, after), "after a caught throw");

    try { deeper(5); } catch (Error e) {}
    maskOf(after);
    check(sameMask(before, after), "after a throw through defers");

    try {
        try { fails(); } catch (Error e) { throw(e); }
    } catch (Error e) {}
    maskOf(after);
    check(sameMask(before, after), "after a rethrow");

    /* a program run after the throw reports its crash (it inherits the mask) */
    fs.writeAll(TM_SRC, "void main() {\n"
                        "    i64 *p = null;\n"
                        "    *p = 1;\n"
                        "}\n", libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 30) "
               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -run "
               TM_SRC " > /dev/null 2> " TM_ERR);
    u8 *err = fs.readAll(TM_ERR).take();
    check(err && libc.strstr(err, "null pointer dereference") &&
          libc.strstr(err, "ktm-901-crash.ktm:3 in main"), "a crash in a program run after a throw is reported");
    mem.free(err);
    proc.shell("rm -f " TM_SRC " " TM_ERR);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
