import cstr, fs, libc, mem, proc, test;

/* On a filesystem whose readdir() gives DT_UNKNOWN for every entry,
 * fs.removeDir(path, RM_RECURSE) stat()ed each entry and, for a directory,
 * recursed on `path` instead of the entry: endless recursion and a
 * segfault. A DT_UNKNOWN filesystem is simulated with an LD_PRELOAD
 * readdir() shim, so this needs Linux and a C compiler; elsewhere the
 * checks are skipped. Everything happens in a fresh mkdtemp directory. */

extern "c" u8 *mkdtemp(u8 *template);

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
"import fs;\n" \
"i32 main(i32 argc, u8 **argv)\n" \
"{\n" \
"  printf(\"result=%d\\n\", fs.removeDir(argv[1], fs.REMOVE_RECURSE));\n" \
"  return 0;\n" \
"}\n"

bool exists(u8 *path) {
    libc.cStat st;
    return libc.lstat(path, &st) == 0;
}

i64 runFn(u8 *fmt, u8 *root) {
    u8 *cmd = cstr.formatTo(null, fmt, root, root, root, root, root, root);
    i64 res = proc.shell(cmd);
    mem.free(cmd);
    return res;
}

i32 main() {
    i64 correct = 0, tests = 3;
    u8 root[64], tree[128], *out;
    printf("Test - RmDir recursion on DT_UNKNOWN entries: ");

    mem.copy(root, "/tmp/ktm-test-301-XXXXXX", 25);
    if (!mkdtemp(root)) {
        test.printResult(0, tests);
        printf("====\n");
        return 0;
    }

    fs.writeAll(cstr.formatTo(tree, "%s/dtunk.c", root), SHIM_SRC, cstr.len(SHIM_SRC));
    if (runFn("[ \"$(uname)\" = Linux ] && command -v cc >/dev/null 2>&1 && "
                    "cd '%s' && cc -shared -fPIC -o dtunk.so dtunk.c -ldl", root) != 0) {
        /* No shim possible here: nothing to test */
        correct = tests;
    } else {
        fs.writeAll(cstr.formatTo(tree, "%s/p.ktm", root), PROG_SRC, cstr.len(PROG_SRC));
        runFn("mkdir -p '%s/t/a/b/c' '%s/t/d' && echo x > '%s/t/a/f' && "
                "echo y > '%s/t/a/b/c/g' && echo z > '%s/t/top'", root);
        cstr.formatTo(tree, "%s/t", root);
        if (runFn("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                        "'%s/p.ktm' -o '%s/p' && cd '%s' && "
                        "LD_PRELOAD='%s/dtunk.so' ./p '%s/t' > '%s/out' 2>&1",
                        root) == 0) {
            correct++;
        }
        out = fs.readAll(cstr.formatTo(tree, "%s/out", root));
        if (out && libc.strstr(out, "result=0\n")) { correct++; }
        else if (out) { printf("%s\n", out); }
        if (out) { mem.free(out); }
        if (!exists(cstr.formatTo(tree, "%s/t", root))) { correct++; }
    }

    runFn("rm -rf '%s'", root);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
