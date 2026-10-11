import test;

import fs, libc, mem, proc, str;

/* With `#define M "mid"`, `u8 *s = "a" M "b";` was silently left out of
 * -transpile's C: the transpiler keeps #defines, so the parser met an
 * identifier between two strings and gave up on the statement. The
 * strings and string macros are now written as they are (`"a" M "b"`),
 * which C pastes together the same way. */

#define TP_FILE "__229_transpile.ktm"
#define TP_C    "__229_transpile.c"
#define TP_EXE  "__229_transpile.exe"
#define TP_OUT  "__229_transpile.out"
#define TP_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
#define TP_KTM TP_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

void cleanupTranspilePasteTest(void) {
    proc.shell("rm -f " TP_FILE " " TP_C " " TP_EXE " " TP_OUT);
}

u8 *tpSource =
"import str;

#define M \"mid\"
#define N \"end\"
#define TAB \"\\t\"
#define K 3
#define HALF 0.5
void main()
{
  u8 *s = \"a\" M \"b\";
  u8 *t = M \"x\";
  u8 *u = \"x\" M;
  u8 *v = M N;
  u8 *w = M;
  u8 *e = \"[\" TAB \"\\n]\";
  u8 *p = \"plain\" \" pair\";
  i64 k = K * 2 + 1;
  f64 h = HALF * k;
  printf(\"%s %s %s %s %s %d %.1f\\n\", s, t, u, v, w, k, h);
  printf(\"fmt \" M \" %d\\n\", k);
  printf(\"%s|%s|%d\\n\", \"q\" M N, p, str.len(e));
}
";

u8 *tpExpected = "amidb midx xmid midend mid 7 3.5\nfmt mid 7\nqmidend|plain pair|4\n";

void main() {
    printf("Test - transpile macro pasted into a string: ");
    i64 correct = 0, tests = 0, res;
    u8 *code, *out;

    cleanupTranspilePasteTest();
    fs.writeAll(TP_FILE, tpSource, str.len(tpSource), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);

    /* What the JIT prints */
    tests++;
    res = proc.shell(TP_KTM "-jit " TP_FILE " > " TP_OUT " 2>&1");
    out = fs.readAll(TP_OUT);
    if (res == 0 && out && !str.cmp(out, tpExpected)) { correct++; }
    else { printf("jit: %s\n", out); }
    mem.free(out);

    tests++;
    res = proc.shell(TP_KTM "-transpile " TP_FILE " > " TP_C " 2>/dev/null");
    code = fs.readAll(TP_C);
    if (res == 0 && code) { correct++; }
    if (!code) { code = str.dup(""); }

    /* Every statement is there, the pastes as written */
    tests++;
    if (libc.strstr(code, "unsigned char *s = \"a\" M \"b\";") &&
            libc.strstr(code, "unsigned char *t = M \"x\";") &&
            libc.strstr(code, "unsigned char *u = \"x\" M;") &&
            libc.strstr(code, "unsigned char *v = M N;") &&
            libc.strstr(code, "unsigned char *w = M;") &&
            libc.strstr(code, "unsigned char *e = \"[\" TAB \"\\n]\";") &&
            libc.strstr(code, "unsigned char *p = \"plain pair\";") &&
            libc.strstr(code, "printf(\"fmt \" M \" %d\\n\", k);") &&
            libc.strstr(code, "\"q\" M N")) { correct++; }
    else { printf("%s\n", code); }

    /* ...and the macros they use are defined */
    tests++;
    if (libc.strstr(code, "#define M \"mid\"") && libc.strstr(code, "#define N \"end\"") &&
            libc.strstr(code, "#define TAB \"\\t\"") && libc.strstr(code, "#define K 3")) { correct++; }

    /* The C compiles and prints what the JIT does (only with a C
     * compiler on the PATH) */
    tests++;
    if (proc.shell("command -v cc >/dev/null 2>&1") != 0) {
        correct++;
    } else {
        res = proc.shell("cc -w -o " TP_EXE " " TP_C " -lm > " TP_OUT " 2>&1 && " TP_TIMEOUT "./" TP_EXE " > " TP_OUT " 2>&1");
        out = fs.readAll(TP_OUT);
        if (res == 0 && out && !str.cmp(out, tpExpected)) { correct++; }
        else { printf("cc: %s\n", out); }
        mem.free(out);
    }

    mem.free(code);
    cleanupTranspilePasteTest();
    test.printResult(correct, tests);
    printf("====\n");
}
