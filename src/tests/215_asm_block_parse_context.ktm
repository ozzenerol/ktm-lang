import fs, libc, mem, proc, str, test;

/* An asm block failed to parse depending on what came before it
 * ("Identifier expected: got TK_PUNCT \n" on the declaration after the
 * block). The parser switched on whitespace tokens for the block when it
 * reached the `{`, but the lexer reads ahead of the parser through a
 * 16-token ring: if it had already passed the closing `}`, the flag stayed
 * on and the code after the block was lexed with newline tokens. Each
 * program below puts a different number of tokens before the block, so
 * every read-ahead position is covered. x86_64 asm only. */

#define ASMCTX_FILE "__215_asmctx.ktm"
#define ASMCTX_OUT  "__215_asmctx.out"
#define ASMCTX_BIN  "__215_asmctx.bin"

void cleanupFiles() {
    fs.remove(ASMCTX_FILE);
    fs.remove(ASMCTX_OUT);
    fs.remove(ASMCTX_BIN);
}

/* Write `src` and run it (JIT, or compile + run): true if it exits 0 */
bool runSource(u8 *src, bool jit) {
    cleanupFiles();
    if (!fs.writeAll(ASMCTX_FILE, src, str.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    i64 res;
    if (jit) {
        res = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                                 "-jit " ASMCTX_FILE " > " ASMCTX_OUT " 2>&1");
    } else {
        res = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                                 ASMCTX_FILE " -o " ASMCTX_BIN " > " ASMCTX_OUT " 2>&1 && "
                                 "./" ASMCTX_BIN " >> " ASMCTX_OUT " 2>&1");
    }
    if (res != 0) {
        u8 *out = fs.readAll(ASMCTX_OUT);
        if (out) { printf("%s\n", out); }
        mem.free(out);
    }
    cleanupFiles();
    return res == 0;
}

/* `extra` tokens before the block: `i64 g = 1 + 1 ...` adds two tokens
 * per term, a leading `-` one more. */
u8 *makeSource(i64 terms, bool minus) {
    u8 *sign = "";
    if (minus) { sign = "-"; }
    u8 *src = str.formatTo(null, "i64 g_pad = %s1", sign);
    for (i64 i = 0; i < terms; ++i) { src = str.appendFormat(src, " + 1"); }
    src = str.appendFormat(src, ";\n"
"asm {\n"
"_T215_FORTY_TWO::\n"
"    MOV     RAX, 42\n"
"    RET\n"
"}\n"
"pub _extern _T215_FORTY_TWO i64 fortyTwo();\n"
"i64 gAfter = 3;\n"
"i32 main()\n"
"{\n"
"  if (fortyTwo() != 42 || gAfter != 3) { return 1; }\n"
"  return 0;\n"
"}\n");
    return src;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - asm block parse context: ");
#ifdef IS_X86_64
    bool ok = true;
    for (i64 terms = 0; terms < 16; ++terms) {
        for (i64 minus = 0; minus < 2; ++minus) {
            u8 *src = makeSource(terms, minus);
            if (!runSource(src, true)) {
                printf("failed with %d tokens before the block\n", 5 + 2 * terms + minus);
                ok = false;
            }
            mem.free(src);
        }
    }
    tests++; if (ok) { correct++; }

    /* The original report: three extern decls, then an Exit-style binding
     * to a block that has no RET */
    u8 *orig =
"pub extern \"c\" void exit(i32 status);\n"
"pub extern \"c\" i32 system(u8 *command);\n"
"pub extern \"c\" i64 write(i32 fd, void *buf, u64 nbyte);\n"
"asm {\n"
"_T215_EXIT::\n"
"    CALL    exit\n"
"}\n"
"pub _extern _T215_EXIT void t215Exit(i64 exit_code=1);\n"
"i32 main() { t215Exit(0); return 1; }\n";
    tests++; if (runSource(orig, true)) { correct++; }
    tests++; if (runSource(orig, false)) { correct++; }

    /* An inline asm statement inside a function, then more code */
    u8 *inl =
"i64 get()\n"
"{\n"
"  i64 r = 0;\n"
"  asm {\n"
"    MOV RAX, 7\n"
"  }\n"
"  r = 7;\n"
"  return r;\n"
"}\n"
"i32 main() { return get() - 7; }\n";
    tests++; if (runSource(inl, true)) { correct++; }
#else
    tests++; correct++; /* the asm here is x86_64 */
#endif
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
