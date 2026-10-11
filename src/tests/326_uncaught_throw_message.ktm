#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* An uncaught throw printed `uncaught throw: 6574614C` on stdout. It is
 * now reported on stderr, a printable character constant as text, with
 * where the throw is: `uncaught error <code> (<file>:<line>)`. */

#define UT_SRC "__326_uncaught.ktm"
#define UT_BIN "./__326_uncaught.out"
#define UT_OUT "__326_uncaught.stdout"
#define UT_ERR "__326_uncaught.stderr"

bool uncaught(u8 *body, u8 *wantErr, u8 *wantOut) {
    u8 *src = str.formatTo(null, "void Main()\n{\n%s\n}\n", body);
    bool ok = false;
    if (fs.writeAll(UT_SRC, src, str.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC) &&
            proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                         UT_SRC " -o " UT_BIN) == 0) {
        i64 res = proc.shell(UT_BIN " > " UT_OUT " 2> " UT_ERR);
        u8 *out = fs.readAll(UT_OUT), *err = fs.readAll(UT_ERR);
        if (out && err) {
            ok = res == 1 && !str.cmp(err, wantErr) && !str.cmp(out, wantOut);
            if (!ok) { printf("\nexit %d, stdout [%s], stderr [%s]\n", res, out, err); }
        }
        mem.free(out);
        mem.free(err);
    }
    mem.free(src);
    fs.remove(UT_SRC);
    fs.remove(UT_BIN);
    fs.remove(UT_OUT);
    fs.remove(UT_ERR);
    return ok;
}

i32 main() {
    printf("Test - uncaught throw message: ");
    i64 correct = 0, tests = 8;

    if (uncaught("throw('Late');", "uncaught error 'Late' (" UT_SRC ":3)\n", "")) { correct++; }
    if (uncaught("\"before\\n\"; throw('A');",
                             "uncaught error 'A' (" UT_SRC ":3)\n", "before\n")) { correct++; }
    if (uncaught("throw('LongName');", "uncaught error 'LongName' (" UT_SRC ":3)\n", "")) {
        correct++;
    }
    if (uncaught("throw(0x0A41);", "uncaught error 2625 (" UT_SRC ":3)\n", "")) { correct++; }
    if (uncaught("throw(0);", "uncaught error 0 (" UT_SRC ":3)\n", "")) { correct++; }
    if (uncaught("throw(-12);", "uncaught error -12 (" UT_SRC ":3)\n", "")) { correct++; }
    if (uncaught("throw(0x8000000000000000);",
                             "uncaught error -9223372036854775808 (" UT_SRC ":3)\n", "")) { correct++; }
    /* After a try has been used and left, the chain is empty again. */
    if (uncaught("try { throw(1); } catch { \"c\\n\"; } throw('Bye');",
                             "uncaught error 'Bye' (" UT_SRC ":3)\n", "c\n")) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
