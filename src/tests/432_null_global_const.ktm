import test;

import fs, libc, proc, str;

/* A global initialised with `null` (or a cast of an integer constant) is
 * a constant: written as data, as `null` (0) was, not stored by code that
 * runs before main. The -S output has `.quad 0` for it and no store. */

#define N_SRC "./ktm-432-null.ktm"
#define N_OUT "./ktm-432-null.s"

u8 *gP = null;
fn(i64) i64 gFn = null;
i64 *gCast = (i64 *)0;

i32 main() {
    printf("Test - null global initialisers are constants: ");
    i64 correct = 0, tests = 0;

    tests++; if (gP == null && gFn == null && gCast == null) { correct++; }

    u8 *src = "u8 *g_p = null;\nfn(i64) i64 g_fn = null;\ni32 main() { return 0; }\n";
    proc.shell("rm -f " N_SRC " " N_OUT);
    fs.writeAll(N_SRC, src, str.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " N_SRC " -o " N_OUT " 2>&1");
    u8 *s = fs.readAll(N_OUT);
    /* no run-time store into either global */
    tests++; if (s && !libc.strstr(s, "g_p(%rip)") && !libc.strstr(s, "g_fn(%rip)") &&
                             !libc.strstr(s, "g_p@PAGEOFF") && !libc.strstr(s, "g_fn@PAGEOFF") &&
                             !libc.strstr(s, ":lo12:g_p") && !libc.strstr(s, ":lo12:g_fn")) { correct++; }
    proc.shell("rm -f " N_SRC " " N_OUT);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
