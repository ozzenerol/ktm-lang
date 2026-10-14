import cstr, fs, mem, proc, test;

/* `ktm -lib name dir`, dir not module name's directory: every module
 * under dir (web/route is web.route) goes into one library, dir/libname.a
 * and its shared object, and each module's directory gets a .ktmlib
 * marker naming them relative to it, so the tree can move. The top's
 * own files and a top-level prelude/ are not built. The object file is
 * a temporary one (none is left in the tree) and the cc/ar commands are
 * printed only with -v. One module's library (`-lib geo dir/geo`) has a
 * relative marker too. The fixture is src/tests/modules/libtree, copied
 * first; AOT and JIT. */

#define OUT "./ktm-439-tree.out"
#define BIN "./ktm-439-tree.bin"
#define TREE "./ktm-439-tree"
#define MOVED "./ktm-439-moved"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
                        "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

u8 *gWant = "9 5 4\n";

bool output(u8 *cmd, u8 *want) {
    proc.shell("rm -f " OUT " " BIN);
    proc.shell(cstr.view(cmd));
    u8 *out = fs.readAll(OUT).take();
    bool ok = out && !cstr.cmp(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", cmd, out); }
    mem.free(out);
    proc.shell("rm -f " OUT " " BIN);
    return ok;
}

i32 main() {
    printf("Test - one library from a tree of modules: ");
    i64 correct = 0, tests = 0;

    proc.shell("rm -rf " TREE " " MOVED " && mkdir -p " TREE
                 " && cp -r ${KTM_TEST_SRC:-.}/modules/libtree/. " TREE "/");

    /* quiet, one library, a relative marker per module, no object file */
    tests++;
    if (output(KTM "-lib tree " TREE " > " OUT " 2>&1", "")) { correct++; }
    tests++;
    if (output("cat " TREE "/geo/.ktmlib " TREE "/web/route/.ktmlib | grep static > " OUT,
                         "static ../libtree.a\nstatic ../../libtree.a\n")) {
        correct++;
                         }
    tests++;
    if (output("ls " TREE "/libtree.a > /dev/null && test ! -e " TREE "/prelude/.ktmlib && "
                         "test -z \"$(find " TREE " -name '*.o')\" && echo ok > " OUT, "ok\n")) {
        correct++;
                         }

    /* the program links it: none of the modules' code is compiled in */
    tests++;
    if (output(KTM "-S " TREE "/main.ktm -o " BIN " && grep -c '^_K3geo4area:' " BIN " > " OUT,
                         "0\n")) {
        correct++;
                         }
    tests++;
    if (output(KTM TREE "/main.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, gWant)) {
        correct++;
    }
    tests++;
    if (output(KTM "-jit " TREE "/main.ktm > " OUT " 2>&1", gWant)) { correct++; }

    /* the markers are relative: the tree still works moved */
    proc.shell("mv " TREE " " MOVED);
    tests++;
    if (output(KTM MOVED "/main.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, gWant)) {
        correct++;
    }
    tests++;
    if (output(KTM "-jit " MOVED "/main.ktm > " OUT " 2>&1", gWant)) { correct++; }

    /* -v prints the commands; one module's library, relative too */
    proc.shell("rm -f " MOVED "/geo/.ktmlib " MOVED "/web/route/.ktmlib " MOVED "/libtree.*");
    tests++;
    if (output(KTM "-v -lib geo " MOVED "/geo 2>&1 | grep -c 'ar rcs' > " OUT, "1\n")) { correct++; }
    tests++;
    if (output("grep static " MOVED "/geo/.ktmlib > " OUT, "static libgeo.a\n")) { correct++; }

    /* a directory that is neither */
    tests++;
    if (output(KTM "-lib nothing " MOVED "/geo 2>&1 | grep -c 'is neither module nothing.s "
                         "directory (one ending in nothing) nor a tree with modules under it' > " OUT, "1\n")) {
        correct++;
                         }

    proc.shell("rm -rf " TREE " " MOVED " " OUT " " BIN);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
