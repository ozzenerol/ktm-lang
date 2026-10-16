import cstr, fs, libc, mem, proc, test;

/* An uncaught error printed only where its throw was. It now prints the
 * calls that led there too, as a crash report does, compiled and in the
 * JIT:
 *   uncaught error 7: n is 3 (f.ktm:4)
 *     at f.ktm:4 in check
 *     called from f.ktm:10 in walk (inlined)
 *     called from f.ktm:16 in main */

#define UE_SRC "__840_uncaught.ktm"
#define UE_BIN "./__840_uncaught.out"
#define UE_ERR "__840_uncaught.stderr"

/* Compile src (AOT, or run it in the JIT), run it, and check that it
 * exits 1 with exactly want on stderr */
bool uncaught(u8 *src, bool jit, u8 *want) {
    bool ok = false;
    i64 res = -1;
    if (!fs.writeAll(UE_SRC, cstr.view(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    if (jit) {
        res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit "
                         UE_SRC " > /dev/null 2> " UE_ERR);
    } else if (proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                          UE_SRC " -o " UE_BIN) == 0) {
        res = proc.shell(UE_BIN " > /dev/null 2> " UE_ERR);
    }
    u8 *err = fs.readAll(UE_ERR).take();
    if (err) {
        ok = res == 1 && !cstr.cmp(err, want);
        if (!ok) { printf("\n%s: exit %d, stderr [%s]\n", jit ? "jit" : "aot", res, err); }
    }
    mem.free(err);
    fs.remove(UE_SRC);
    fs.remove(UE_BIN);
    fs.remove(UE_ERR);
    return ok;
}

/* a throw two calls down */
#define NESTED "void check(i64 n)\n"                        \
    "{\n"                                                   \
    "    if (n > 2) {\n"                                    \
    "        throw(7, \"n is %d\", n);\n"                   \
    "    }\n"                                               \
    "    printf(\"checked %d\\n\", n);\n"                   \
    "}\n"                                                   \
    "void walk(i64 n)\n"                                    \
    "{\n"                                                   \
    "    check(n);\n"                                       \
    "    printf(\"walked %d\\n\", n);\n"                    \
    "}\n"                                                   \
    "i32 main()\n"                                          \
    "{\n"                                                   \
    "    walk(1);\n"                                        \
    "    walk(3);\n"                                        \
    "    return 0;\n"                                       \
    "}\n"
#define NESTED_ERR "uncaught error 7: n is 3 (" UE_SRC ":4)\n" \
    "  at " UE_SRC ":4 in check\n"                         \
    "  called from " UE_SRC ":10 in walk (inlined)\n"                \
    "  called from " UE_SRC ":16 in main\n"

/* a recursion shows once, with how many times it repeated */
#define DEEP "i64 down(i64 n)\n"                            \
    "{\n"                                                   \
    "    if (n == 0) {\n"                                   \
    "        throw(9, \"bottom\");\n"                       \
    "    }\n"                                               \
    "    printf(\"%d\\n\", n);\n"                           \
    "    return down(n - 1) + 1;\n"                         \
    "}\n"                                                   \
    "i32 main()\n"                                          \
    "{\n"                                                   \
    "    return down(5);\n"                                 \
    "}\n"
#define DEEP_ERR "uncaught error 9: bottom (" UE_SRC ":4)\n" \
    "  at " UE_SRC ":4 in down\n"                          \
    "  called from " UE_SRC ":7 in down (5 times)\n"       \
    "  called from " UE_SRC ":11 in main\n"

/* a failed bounds check: the runtime's own calls are left out */
#define BOUNDS "i64 at(i64[] s, i64 i)\n"                   \
    "{\n"                                                   \
    "    printf(\"at %d\\n\", i);\n"                        \
    "    return s[i];\n"                                    \
    "}\n"                                                   \
    "i32 main()\n"                                          \
    "{\n"                                                   \
    "    i64 nums[3] = {1, 2, 3};\n"                        \
    "    return at(nums, 3);\n"                             \
    "}\n"
#define BOUNDS_ERR "uncaught error 1802792193: index 3 out of range for length 3 (" \
    UE_SRC ":4)\n"                                         \
    "  at " UE_SRC ":4 in at\n"                            \
    "  called from " UE_SRC ":9 in main\n"

/* thrown on from a catch: the calls are those of the throw(e), from its
 * own line (v0.28.1; before, the catch's closing brace, where e is freed
 * and the throw went on) */
#define AGAIN "void inner()\n"                              \
    "{\n"                                                   \
    "    throw(5, \"inner\");\n"                            \
    "}\n"                                                   \
    "void outer()\n"                                        \
    "{\n"                                                   \
    "    try {\n"                                           \
    "        inner();\n"                                    \
    "    } catch (Error e) {\n"                             \
    "        printf(\"seen %d\\n\", e.code);\n"             \
    "        throw(e);\n"                                   \
    "    }\n"                                               \
    "}\n"                                                   \
    "i32 main()\n"                                          \
    "{\n"                                                   \
    "    outer();\n"                                        \
    "    return 0;\n"                                       \
    "}\n"
#define AGAIN_ERR "uncaught error 5: inner (" UE_SRC ":3)\n" \
    "  at " UE_SRC ":11 in outer\n"                        \
    "  called from " UE_SRC ":16 in main\n"

i32 main() {
    printf("Test - uncaught error trace: ");
    i64 correct = 0, tests = 8;

    if (uncaught(NESTED, false, NESTED_ERR)) { correct++; }
    if (uncaught(NESTED, true, NESTED_ERR)) { correct++; }
    if (uncaught(DEEP, false, DEEP_ERR)) { correct++; }
    if (uncaught(DEEP, true, DEEP_ERR)) { correct++; }
    if (uncaught(BOUNDS, false, BOUNDS_ERR)) { correct++; }
    if (uncaught(BOUNDS, true, BOUNDS_ERR)) { correct++; }
    if (uncaught(AGAIN, false, AGAIN_ERR)) { correct++; }
    if (uncaught(AGAIN, true, AGAIN_ERR)) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
