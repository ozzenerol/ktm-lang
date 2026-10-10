"Test - indirect struct return C interop: ";
#include "testhelper.ktm"

/* A >16-byte struct return crosses the C boundary via the hidden
 * out-pointer, and on AArch64 that pointer travels in x8 (AAPCS64's
 * dedicated indirect-result register) - NOT as a first argument the
 * way SysV uses rdi. ktm used to apply the SysV rule on arm64: the
 * callee's real first argument shifted into x1 and the buffer address
 * sat in x0, so calling any clang-compiled function returning a
 * >16-byte struct (e.g. raylib's LoadTexture) scrambled every
 * argument and wrote the result through a stale x8.
 *
 * Two-stage test against a genuinely clang-compiled callee/caller:
 * build a C shared library, then compile a ktm program that
 * exercises both directions of the boundary (ktm caller -> C callee,
 * and C caller -> ktm callback) with both the AOT and JIT backends.
 * No strings or `%` in the generated sources keeps the shell printf
 * simple (same trick as 60_link.ktm). */

I64 correct = 0;
I64 tests = 3;

if (System("printf '"
           "typedef struct T { unsigned id; int w; int h; int mip; int fmt; } T;\n"
           "typedef struct B { long a; long b; long c; } B;\n"
           "T CMakeTex(int seed) { T t; t.id = seed; t.w = 640; t.h = 480; t.mip = 1; t.fmt = 7; return t; }\n"
           "long CCallsBack(B (*fn)(long)) { B b = fn(10); return b.a + b.b + b.c; }\n"
           "' > ./ktm-64-sret.c && "
           "cc -shared -fPIC ./ktm-64-sret.c -o ./ktm-64-sret.so") == 0) {
  correct++;
}

System("printf '"
       "#link \"'\"$PWD\"'/ktm-64-sret.so\"\n"
       "class T { U32 id; I32 w, h, mip, fmt; };\n"
       "class B { I64 a, b, c; };\n"
       "extern \"c\" T CMakeTex(I32 seed);\n"
       "extern \"c\" I64 CCallsBack(U0 *fn);\n"
       "B HcMakeBig(I64 n) { B b; b.a = n; b.b = n * 2; b.c = n * 3; return b; }\n"
       "I32 Main() {\n"
       "  T t = CMakeTex(8);\n"
       "  if (t.id != 8 || t.w != 640 || t.h != 480 || t.mip != 1 || t.fmt != 7) return 1;\n"
       "  if (CCallsBack((&HcMakeBig)(U0 *)) != 60) return 2;\n"
       "  return 0;\n"
       "}\n"
       "' > ./ktm-64-sret.ktm");

if (System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} ./ktm-64-sret.ktm -o ./ktm-64-sret && "
           "./ktm-64-sret") == 0) {
  correct++;
}

if (System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit ./ktm-64-sret.ktm") == 0) {
  correct++;
}

System("rm -f ./ktm-64-sret.c ./ktm-64-sret.so"
       " ./ktm-64-sret.ktm ./ktm-64-sret");

PrintResult(correct, tests);
'===\n';
Exit(0);
