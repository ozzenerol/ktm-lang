#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* `return` in top-level code (`if (x) return 0;` outside any function)
 * crashed ktm, AOT and JIT: parseReturnStatement used the current
 * function's name and return type, which are null there. It is an error
 * at the `return` now. Also checks that a file ending inside an unclosed
 * block, (, [, string, char, comment or class body gives an error with
 * its location rather than a crash. */

#define RO_SRC "371-src.ktm"
#define RO_OUT "371-src.out"

/* Compile `source` (JIT when jit) and return what ktm printed, or null
 * when ktm did not exit with 1 (a crash or a success). */
u8 *diagnose(u8 *source, bool jit) {
    u8 *cmd, *out = null, *mode = "-S -o /dev/null";
    i64 res;
    if (jit) { mode = "-jit"; }
    proc.shell("rm -f " RO_SRC " " RO_OUT);
    fs.writeAll(RO_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    cmd = str.format("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                                    "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                                    "%s " RO_SRC " > " RO_OUT " 2>&1",
                                    mode);
    res = proc.shell(cmd);
    if (res == 1) { out = fs.readAll(RO_OUT); }
    else { printf("ktm exited with %d\n", res); }
    proc.shell("rm -f " RO_SRC " " RO_OUT);
    mem.free(cmd);
    return out;
}

/* AOT and JIT both fail cleanly and print `msg` and `where` */
bool prints(u8 *source, u8 *msg, u8 *where) {
    bool ok = true;
    for (i64 jit = 0; jit < 2; ++jit) {
        u8 *out = diagnose(source, jit);
        if (!out || !libc.strstr(out, msg) || !libc.strstr(out, where)) {
            if (jit) { printf("JIT: "); }
            else     { printf("AOT: "); }
            printf("got: '%s'\n", out);
            ok = false;
        }
        mem.free(out);
    }
    return ok;
}

i32 main() {
    printf("Test - return outside a function: ");
    i64 tests = 0;
    i64 correct = 0;

    tests++;
    if (prints("if (1) {\n  return 0;", "`return` outside a function",
                         RO_SRC ":2:3\n")) { correct++; }
    else { printf("Failed - return in an unclosed top-level block\n"); }

    tests++;
    if (prints("i64 x = 1;\nif (x) return 0;\n", "`return` outside a function",
                         RO_SRC ":2:8\n")) { correct++; }
    else { printf("Failed - return in a top-level if\n"); }

    tests++;
    if (prints("void F() { return; }\nwhile (1) {\n  return;\n}\n",
                         "`return` outside a function", RO_SRC ":3:3\n")) { correct++; }
    else { printf("Failed - return in a top-level while after a function\n"); }

    /* end of file inside an unclosed construct */
    tests++;
    if (prints("i32 main()\n{\n  if (1) {\n    return 0;\n",
                         "Unexpected end of input, expected `}`", RO_SRC ":4")) { correct++; }
    else { printf("Failed - unclosed {\n"); }

    tests++;
    if (prints("i64 x = (1 + 2", "Unexpected end of input, expected `)`",
                         RO_SRC ":1")) { correct++; }
    else { printf("Failed - unclosed (\n"); }

    tests++;
    if (prints("i64 x[4];\ni32 main()\n{\n  return x[1",
                         "Unexpected end of input, expected `]`", RO_SRC ":4")) { correct++; }
    else { printf("Failed - unclosed [\n"); }

    tests++;
    if (prints("u8 *s = \"abc", "Unterminated string", RO_SRC ":1:9\n")) { correct++; }
    else { printf("Failed - unclosed string\n"); }

    tests++;
    if (prints("i32 Main()\n{\n  u8 c = 'a", "Unterminated char const",
                         RO_SRC ":3:10\n")) { correct++; }
    else { printf("Failed - unclosed char\n"); }

    tests++;
    if (prints("i32 main()\n{\n  /* comment\n  return 0;\n",
                         "unterminated comment", RO_SRC ":3:3\n")) { correct++; }
    else { printf("Failed - unclosed comment\n"); }

    tests++;
    if (prints("struct Foo\n{\n  i64 a;\n", "Unexpected end of input in class/union body",
                         RO_SRC ":3")) { correct++; }
    else { printf("Failed - unclosed class body\n"); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
