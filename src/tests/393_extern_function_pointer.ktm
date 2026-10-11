#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* `extern i64 (*fp)(i64);` didn't parse ("`extern <Type>` must be followed
 * by an identifier, got `(`"), so a program couldn't use a library's
 * public function-pointer global. It now declares one, like
 * `extern i64 x;` does for data. */

#define XF_SRC "__393_xf.ktm"
#define XF_OUT "__393_xf.out"
#define XF_BIN "__393_xf.bin"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* ktm's output (stdout and stderr) for `src` with `flags`; *_res gets the
 * exit status */
u8 *ktm(u8 *src, u8 *flags, i64 *_res) {
    u8 *cmd;
    fs.writeAll(XF_SRC, src, str.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    cmd = str.format("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                                    "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                                    "%s " XF_SRC " > " XF_OUT " 2>&1", flags);
    *_res = proc.shell(cmd);
    mem.free(cmd);
    return fs.readAll(XF_OUT);
}

i32 main() {
    i64 res;
    u8 *out;
    printf("Test - extern function pointer: ");

    /* declared extern, then defined in the same file */
    out = ktm(
"i64 twice(i64 v) { return v * 2; }
extern fn(i64) i64 fp;
fn(i64) i64 fp = &twice;
void main() { printf(\"%d\\n\", fp(4)); }
", "-jit", &res);
    check(res == 0 && out && !str.cmp(out, "8\n"), "extern then definition (JIT)");
    mem.free(out);

    /* a malformed extern still says what is wrong */
    out = ktm("extern i64 123;\n", "-jit", &res);
    check(res != 0 && out &&
                libc.strstr(out, "`extern <Type>` must be followed by an identifier, got `123`"),
                "malformed extern");
    mem.free(out);
    out = ktm("extern i64 (*)(i64);\n", "-jit", &res);
    check(res != 0 && out && libc.strstr(out, "Expected function pointer name"),
                "extern function pointer without a name");
    mem.free(out);
    proc.shell("rm -f " XF_SRC " " XF_OUT " " XF_BIN);

    /* a -lib library's public function pointer, called from a program */
    proc.shell("rm -rf ./ktm-393-prefix ./ktm-393-work ./ktm-393-use.ktm ./ktm-393-use");
    check(proc.shell("import libc;\n\nD=${KTM_INSTALL_DIR:-/usr/local} && H=$PWD/../../ktm && P=$PWD && "
                     "libc.mkdir -p ./ktm-393-prefix/include ./ktm-393-prefix/lib ./ktm-393-work && "
                     "cp $D/include/std.ktmh ./ktm-393-prefix/include/ && "
                     "cp -P $D/lib/libktm.* ./ktm-393-prefix/lib/ && "
                     "printf 'i64 LibTriple(i64 x) { return x * 3; }\\n"
                     "i64 LibSquare(i64 x) { return x * x; }\\n"
                     "pub i64 (*triple)(i64) = &LibTriple;\\n"
                     "pub void Ktm393UseSquare() { triple = &LibSquare; }\\n'"
                     " > ./ktm-393-work/ktm393.ktm && "
                     "cd ./ktm-393-work && "
                     "$H -lib ktm393 --install-dir=$P/ktm-393-prefix ktm393.ktm > /dev/null 2>&1") == 0,
                "build the -lib library");
    /* calls it, then sees the library repoint it */
    proc.shell("printf '#link <ktm393>\\n"
                 "extern i64 (*triple)(i64);\\nvoid Ktm393UseSquare();\\n"
                 "i32 main() { if (triple(4) != 12) return 1; Ktm393UseSquare();"
                 " if (triple(5) != 25) return 2; return 0; }\\n'"
                 " > ./ktm-393-use.ktm");
    check(proc.shell("../../ktm -jit --install-dir=./ktm-393-prefix ./ktm-393-use.ktm") == 0,
                "library function pointer (JIT)");
    check(proc.shell("../../ktm --install-dir=./ktm-393-prefix ./ktm-393-use.ktm -o ./ktm-393-use && "
                             "./ktm-393-use") == 0,
                "library function pointer (AOT)");
    proc.shell("rm -rf ./ktm-393-prefix ./ktm-393-work ./ktm-393-use.ktm ./ktm-393-use");

    PrintResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
