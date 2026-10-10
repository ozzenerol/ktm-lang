#include "testhelper.ktm"

/* `private` (Y14): a struct field marked `private` can be used anywhere
 * in the module that defines the struct (any of its files, its methods)
 * and nowhere outside: reading, writing, `&`, `->`, initialiser lists
 * that reach it, range-for over it, and through a struct that inherits
 * it. Outside, sizeof, copies and passing by value still work. In the
 * main program `private` fields are an error (nothing to hide from), as
 * is `public` on a field. On a declaration `private` means what leaving
 * out `pub` means; both together are an error. The fixture is
 * src/tests/modules/private.
 * F13: a string literal is never a keyword: `F("private", "")` and
 * `F("public")` are calls with string arguments. */

#define P_OUT "./ktm-428-private.out"
#define P_BIN "./ktm-428-private.bin"
#define P_DIR "./p428"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U8 *g_want = "3 24 5 hello lit 3\n";

Bool Output(U8 *cmd, U8 *want)
{
  System("rm -f " P_OUT " " P_BIN);
  System(cmd);
  U8 *out = FileRead(P_OUT);
  Bool ok = out && !StrCmp(out, want);
  if (!ok) "\n%s\n----\n%s\n", cmd, out;
  Free(out);
  System("rm -f " P_OUT " " P_BIN);
  return ok;
}

I64 Count(U8 *s, U8 *what)
{
  I64 n = 0;
  while (s && (s = strstr(s, what))) {
    n++;
    s++;
  }
  return n;
}

/* A program `main` (and the module `shop` when given) next to a copy of
 * net fails with exactly one error, `want` */
Bool Fails(U8 *main, U8 *shop, U8 *want)
{
  System("rm -rf " P_DIR " && mkdir -p " P_DIR " && "
         "cp -r ${KTM_TEST_SRC:-.}/modules/private/net " P_DIR "/");
  FileWrite(P_DIR "/main.ktm", main, StrLen(main), O_CREAT | O_RDWR | O_TRUNC);
  if (shop) {
    System("mkdir -p " P_DIR "/shop");
    FileWrite(P_DIR "/shop/shop.ktm", shop, StrLen(shop), O_CREAT | O_RDWR | O_TRUNC);
  }
  System(KTM "-S " P_DIR "/main.ktm -o /dev/null > " P_OUT " 2>&1");
  U8 *out = FileRead(P_OUT);
  Bool ok = out && Count(out, "error:") == 1 && strstr(out, want);
  if (!ok) "\n%s\n----\n%s\n", main, out;
  Free(out);
  System("rm -rf " P_DIR " " P_OUT);
  return ok;
}

U8 g_buf[64];
u8 *Join(u8 *a, u8 *b = "", u8 *c = "")
{
  StrPrint(g_buf, "%s|%s|%s", a, b, c);
  return g_buf;
}

#define FIXTURE "${KTM_TEST_SRC:-.}/modules/private/main.ktm"

i32 Main()
{
  "Test - private fields: ";
  i64 correct = 0, tests = 0;

  /* F13 */
  tests++; if (!StrCmp(Join("private", ""), "private||")) correct++;
  tests++; if (!StrCmp(Join("public"), "public||")) correct++;
  tests++; if (!StrCmp(Join("pub", "private", "public"), "pub|private|public")) correct++;
  tests++; if (!StrCmp(Join("I64", "u8"), "I64|u8|")) correct++;

  /* inside net every file uses them, outside the layout still works */
  tests++;
  if (Output(KTM FIXTURE " -o " P_BIN " > " P_OUT " 2>&1 && " P_BIN " >> " P_OUT " 2>&1",
             g_want))
    correct++;
  tests++;
  if (Output(KTM "-jit " FIXTURE " > " P_OUT " 2>&1", g_want)) correct++;

  tests++;
  if (Fails("import net;\ni32 main() { net.Conn *c = net.dial(1); u8 *b = c.buf; return 0; }\n",
            NULL, "`buf` is a private field of net.Conn"))
    correct++;
  tests++;
  if (Fails("import net;\ni32 main() { net.Conn *c = net.dial(1); c->buf = NULL; return 0; }\n",
            NULL, "`buf` is a private field of net.Conn"))
    correct++;
  tests++;
  if (Fails("import net;\ni32 main() { net.Conn c; i64 *p = &c.used; return 0; }\n",
            NULL, "`used` is a private field of net.Conn"))
    correct++;
  tests++;
  if (Fails("import net;\ni32 main() { net.Conn c = {1, \"x\"}; return 0; }\n",
            NULL, "`buf` is a private field of net.Conn"))
    correct++;
  tests++;
  if (Fails("import net;\ni32 main() { net.Tls t; t.used = 1; return 0; }\n",
            NULL, "`used` is a private field of net.Conn"))
    correct++;
  tests++;
  if (Fails("import net;\ni64 F(net.Bytes *b) { for (auto x : b) {} return 0; }\n"
            "i32 main() { return 0; }\n",
            NULL, "`size` is a private field of net.Bytes"))
    correct++;
  tests++;
  if (Fails("import shop;\ni32 main() { shop.go(); return 0; }\n",
            "module shop;\nimport net;\npub void go() { net.Conn *c = net.dial(1); c.used = 2; }\n",
            "`used` is a private field of net.Conn"))
    correct++;
  /* `private` on a function: the same as no `pub` */
  tests++;
  if (Fails("import net;\ni32 main() { return net.twice(1); }\n",
            NULL, "`twice` is private to module net"))
    correct++;
  tests++;
  if (Fails("struct S { private i64 x; };\ni32 main() { return 0; }\n", NULL,
            "`private` fields are for the structs of a module"))
    correct++;
  tests++;
  if (Fails("struct S { public i64 x; };\ni32 main() { return 0; }\n", NULL,
            "a field is public unless it is marked `private`"))
    correct++;
  tests++;
  if (Fails("pub private i64 F() { return 0; }\ni32 main() { return 0; }\n", NULL,
            "a declaration is either `pub` or `private`, not both"))
    correct++;
  tests++;
  if (Fails("private pub i64 F() { return 0; }\ni32 main() { return 0; }\n", NULL,
            "a declaration is either `pub` or `private`, not both"))
    correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
