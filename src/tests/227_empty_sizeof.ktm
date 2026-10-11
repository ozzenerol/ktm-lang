import fs, libc, mem, proc, str, test;

/* `sizeof()` (and `sizeof;`, `alignof()`) segfaulted under -jit, AOT and
 * -transpile: the missing operand parsed as null. It is now an error at
 * the token where the operand should be. */

#define SZ_SRC "./ktm-227-sz.ktm"
#define SZ_OUT "./ktm-227-sz.out"

/* Run ktm with `flags` on `source`; `*_status` gets its exit status */
u8 *diagnose(u8 *source, u8 *flags, i64 *statusArg) {
    proc.shell("rm -f " SZ_SRC " " SZ_OUT);
    fs.writeAll(SZ_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    u8 *cmd = str.format("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "%s " SZ_SRC " > " SZ_OUT " 2>&1", flags);
    *statusArg = proc.shell(cmd);
    mem.free(cmd);
    u8 *out = fs.readAll(SZ_OUT);
    proc.shell("rm -f " SZ_SRC " " SZ_OUT);
    return out;
}

/* Fails with status 1 (not a crash) under -jit, AOT and -transpile, and
 * the -jit output has `want` and `where` */
bool rejected(u8 *source, u8 *want, u8 *where) {
    i64 status, aotStatus, trStatus;
    u8 *out = diagnose(source, "-jit", &status);
    u8 *aot = diagnose(source, "-S -o /dev/null", &aotStatus);
    u8 *tr = diagnose(source, "-transpile", &trStatus);
    bool ok = status == 1 && aotStatus == 1 && trStatus == 1 && out &&
                        libc.strstr(out, want) && libc.strstr(out, where);
    if (!ok) { printf("got %d %d %d: '%s'\n", status, aotStatus, trStatus, out); }
    mem.free(out);
    mem.free(aot);
    mem.free(tr);
    return ok;
}

#define NEED "`sizeof` needs a type or an expression"

struct Sz { i64 a; u8 b; };

i32 main() {
    printf("Test - sizeof with no operand: ");
    i64 tests = 0, correct = 0;

    tests++;
    if (rejected("void main() { i64 x = sizeof(); }\n", NEED,
                             "227-sz.ktm:1:31\n")) { correct++; }
    tests++;
    if (rejected("void main() { i64 x = 1 + sizeof() * 2; }\n", NEED,
                             "227-sz.ktm:1:36\n")) { correct++; }
    tests++;
    if (rejected("void main() { i64 a[sizeof()]; }\n", NEED,
                             "227-sz.ktm:1:29\n")) { correct++; }
    tests++;
    if (rejected("i64 g = sizeof();\nvoid main() {}\n", NEED,
                             "227-sz.ktm:1:17\n")) { correct++; }
    tests++;
    if (rejected("void main() { i64 x = sizeof; }\n", NEED,
                             "227-sz.ktm:1:29\n")) { correct++; }
    tests++;
    if (rejected("void main() { i64 x = sizeof(()); }\n", NEED,
                             "227-sz.ktm:1:33\n")) { correct++; }
    tests++;
    if (rejected("void main() { i64 x = alignof(); }\n",
                             "`alignof` needs a type or an expression",
                             "227-sz.ktm:1:32\n")) { correct++; }
    tests++;
    if (rejected("void Main() { i64 x = sizeof(", "Unexpected end of input",
                             "227-sz.ktm:1")) { correct++; }
    /* Recovery goes on to the next error */
    tests++;
    i64 status;
    u8 *out = diagnose("void main()\n{\n  i64 x = sizeof();\n  i64 y = sizeof();\n}\n",
                                         "-jit", &status);
    if (status == 1 && out && libc.strstr(out, "227-sz.ktm:3:19\n") &&
            libc.strstr(out, "227-sz.ktm:4:19\n")) { correct++; }
    else { printf("got: '%s'\n", out); }
    mem.free(out);

    /* The valid forms are unchanged */
    tests++;
    i64 v = 5;
    i64 arr[sizeof(i64)];
    if (sizeof(i64) == 8 && sizeof(v) == 8 && sizeof v == 8 &&
            sizeof("abc") == 4 && sizeof(Sz) == 16 && sizeof(i64 *) == 8 &&
            sizeof(v) * 2 == 16 && sizeof(arr) == 64 && alignof(i64) == 8 &&
            alignof(v) == 8) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
