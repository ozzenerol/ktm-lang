#include "testhelper.ktm"

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

U0 CleanupFiles()
{
  Rm(ASMCTX_FILE);
  Rm(ASMCTX_OUT);
  Rm(ASMCTX_BIN);
}

/* Write `src` and run it (JIT, or compile + run): TRUE if it exits 0 */
Bool RunSource(U8 *src, Bool jit)
{
  CleanupFiles;
  if (!FileWrite(ASMCTX_FILE, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  I64 res;
  if (jit) {
    res = System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-jit " ASMCTX_FILE " > " ASMCTX_OUT " 2>&1");
  } else {
    res = System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 ASMCTX_FILE " -o " ASMCTX_BIN " > " ASMCTX_OUT " 2>&1 && "
                 "./" ASMCTX_BIN " >> " ASMCTX_OUT " 2>&1");
  }
  if (res != 0) {
    U8 *out = FileRead(ASMCTX_OUT);
    if (out) "%s\n", out;
    Free(out);
  }
  CleanupFiles;
  return res == 0;
}

/* `extra` tokens before the block: `I64 g = 1 + 1 ...` adds two tokens
 * per term, a leading `-` one more. */
U8 *MakeSource(I64 terms, Bool minus)
{
  U8 *sign = "";
  if (minus) sign = "-";
  U8 *src = StrPrint(NULL, "I64 g_pad = %s1", sign);
  for (I64 i = 0; i < terms; ++i) src = CatPrint(src, " + 1");
  src = CatPrint(src, ";\n"
"asm {\n"
"_T215_FORTY_TWO::\n"
"    MOV     RAX, 42\n"
"    RET\n"
"}\n"
"public _extern _T215_FORTY_TWO I64 FortyTwo();\n"
"I64 g_after = 3;\n"
"I32 Main()\n"
"{\n"
"  if (FortyTwo() != 42 || g_after != 3) return 1;\n"
"  return 0;\n"
"}\n");
  return src;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - asm block parse context: ";
#ifdef IS_X86_64
  Bool ok = TRUE;
  for (I64 terms = 0; terms < 16; ++terms) {
    for (I64 minus = 0; minus < 2; ++minus) {
      U8 *src = MakeSource(terms, minus);
      if (!RunSource(src, TRUE)) {
        "failed with %d tokens before the block\n", 5 + 2 * terms + minus;
        ok = FALSE;
      }
      Free(src);
    }
  }
  tests++; if (ok) correct++;

  /* The original report: three extern decls, then an Exit-style binding
   * to a block that has no RET */
  U8 *orig =
"public extern \"c\" U0 exit(I32 status);\n"
"public extern \"c\" I32 system(U8 *command);\n"
"public extern \"c\" I64 write(I32 fd, U0 *buf, U64 nbyte);\n"
"asm {\n"
"_T215_EXIT::\n"
"    CALL    exit\n"
"}\n"
"public _extern _T215_EXIT U0 T215Exit(I64 exit_code=1);\n"
"I32 Main() { T215Exit(0); return 1; }\n";
  tests++; if (RunSource(orig, TRUE)) correct++;
  tests++; if (RunSource(orig, FALSE)) correct++;

  /* An inline asm statement inside a function, then more code */
  U8 *inl =
"I64 Get()\n"
"{\n"
"  I64 r = 0;\n"
"  asm {\n"
"    MOV RAX, 7\n"
"  }\n"
"  r = 7;\n"
"  return r;\n"
"}\n"
"I32 Main() { return Get() - 7; }\n";
  tests++; if (RunSource(inl, TRUE)) correct++;
#else
  tests++; correct++; /* the asm here is x86_64 */
#endif
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
