#include "testhelper.ktm"

/* On a filesystem whose readdir() gives DT_UNKNOWN for every entry,
 * RmDir(path, RM_RECURSE) stat()ed each entry and, for a directory,
 * recursed on `path` instead of the entry: endless recursion and a
 * segfault. A DT_UNKNOWN filesystem is simulated with an LD_PRELOAD
 * readdir() shim, so this needs Linux and a C compiler; elsewhere the
 * checks are skipped. Everything happens in a fresh mkdtemp directory. */

extern "c" U8 *mkdtemp(U8 *template);

#define SHIM_SRC \
"#define _GNU_SOURCE\n" \
"#include <dirent.h>\n" \
"#include <dlfcn.h>\n" \
"struct dirent *readdir(DIR *d) {\n" \
"  static struct dirent *(*real)(DIR *);\n" \
"  if (!real) real = dlsym(RTLD_NEXT, \"readdir\");\n" \
"  struct dirent *e = real(d);\n" \
"  if (e) e->d_type = DT_UNKNOWN;\n" \
"  return e;\n" \
"}\n"

#define PROG_SRC \
"I32 Main(I32 argc, U8 **argv)\n" \
"{\n" \
"  \"result=%d\\n\", RmDir(argv[1], RM_RECURSE);\n" \
"  return 0;\n" \
"}\n"

Bool Exists(U8 *path)
{
  cStat st;
  return lstat(path, &st) == 0;
}

I64 Run(U8 *fmt, U8 *root)
{
  U8 *cmd = StrPrint(NULL, fmt, root, root, root, root, root, root);
  I64 res = System(cmd);
  Free(cmd);
  return res;
}

I32 Main()
{
  I64 correct = 0, tests = 3;
  U8 root[64], tree[128], *out;
  "Test - RmDir recursion on DT_UNKNOWN entries: ";

  MemCpy(root, "/tmp/ktm-test-301-XXXXXX", 25);
  if (!mkdtemp(root)) {
    PrintResult(0, tests);
    "====\n";
    return 0;
  }

  FileWrite(StrPrint(tree, "%s/dtunk.c", root), SHIM_SRC, StrLen(SHIM_SRC));
  if (Run("[ \"$(uname)\" = Linux ] && command -v cc >/dev/null 2>&1 && "
          "cd '%s' && cc -shared -fPIC -o dtunk.so dtunk.c -ldl", root) != 0) {
    /* No shim possible here: nothing to test */
    correct = tests;
  } else {
    FileWrite(StrPrint(tree, "%s/p.ktm", root), PROG_SRC, StrLen(PROG_SRC));
    Run("mkdir -p '%s/t/a/b/c' '%s/t/d' && echo x > '%s/t/a/f' && "
        "echo y > '%s/t/a/b/c/g' && echo z > '%s/t/top'", root);
    StrPrint(tree, "%s/t", root);
    if (Run("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
            "'%s/p.ktm' -o '%s/p' && cd '%s' && "
            "LD_PRELOAD='%s/dtunk.so' ./p '%s/t' > '%s/out' 2>&1",
            root) == 0) {
      correct++;
    }
    out = FileRead(StrPrint(tree, "%s/out", root));
    if (out && strstr(out, "result=0\n")) correct++;
    else if (out) "%s\n", out;
    if (out) Free(out);
    if (!Exists(StrPrint(tree, "%s/t", root))) correct++;
  }

  Run("rm -rf '%s'", root);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
