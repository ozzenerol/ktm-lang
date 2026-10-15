import cstr, fs, libc, mem, proc, test;

/* An error in an asm function (a block with `NAME::` labels) was
 * reported one line low, on the instruction after the bad one: the
 * function's text starts with the rest of the label's line, but its
 * first line was counted as the line below the label. Inline asm
 * statements were already right. Each case is compiled with -S for a
 * fixed target, so the x86_64 and arm64 cases run on every host. */

#define DIR "./ktm-886"
#define OUT DIR "/out"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
                        "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

bool reports(u8 *name, u8 *src, u8 *target, u8 *want) {
    u8 *path = cstr.format(DIR "/%s.ktm", name);
    fs.writeAll(cstr.view(path), cstr.view(src));
    u8 *cmd = cstr.format(KTM "--target=%s -S %s -o " DIR "/out.s > " OUT " 2>&1",
                          target, path);
    proc.shell(cstr.view(cmd));
    u8 *out = fs.readAll(OUT).take();
    u8 *full = cstr.format("%s:%s", path, want);
    bool ok = out && libc.strstr(out, full) != null;
    if (!ok) { printf("\nwant %s\n----\n%s\n", full, out); }
    mem.free(out);
    mem.free(full);
    mem.free(cmd);
    mem.free(path);
    return ok;
}

i32 main() {
    printf("Test - asm error line: ");
    i64 correct = 0, tests = 0;
    proc.shell("rm -rf " DIR " && mkdir -p " DIR);

    /* the bad MOV is line 3, not the RET on line 4 */
    tests++;
    if (reports("one", "asm {\nFOO::\n    MOV RAX\n    RET\n}\n"
                       "i32 main() { return 0; }\n",
                "x86_64-unknown-linux-gnu", "3:5")) {
        correct++;
    }
    /* the second function, after blank lines */
    tests++;
    if (reports("two", "asm {\nFOO::\n    MOV RAX, 1\n    RET\n\nBAR::\n"
                       "    PUSH RBP\n\n    MOV RAX\n    RET\n}\n"
                       "i32 main() { return 0; }\n",
                "x86_64-unknown-linux-gnu", "9:5")) {
        correct++;
    }
    /* an instruction on the label's own line */
    tests++;
    if (reports("same", "asm {\nFOO:: MOV RAX\n    RET\n}\n"
                        "i32 main() { return 0; }\n",
                "x86_64-unknown-linux-gnu", "2:")) {
        correct++;
    }
    /* an inline asm statement */
    tests++;
    if (reports("inline", "i32 main() {\n    asm {\n        NOP\n        MOV RAX\n"
                          "    }\n    return 0;\n}\n",
                "x86_64-unknown-linux-gnu", "4:9")) {
        correct++;
    }
    /* arm64 */
    tests++;
    if (reports("arm", "asm {\nFOO::\n    MOV X0, 1\n    MOV X0\n    RET\n}\n"
                       "i32 main() { return 0; }\n",
                "aarch64-unknown-linux-gnu", "4:5")) {
        correct++;
    }

    proc.shell("rm -rf " DIR);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
