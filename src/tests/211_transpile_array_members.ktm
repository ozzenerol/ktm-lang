import fs, libc, mem, proc, str, test;

/* `-transpile` wrote array sizes in bytes (`Leaf leaves[2];` became
 * `Leaf leaves[32];`), kept only one dimension of `i32 grid[2][3]`, and
 * wrote `a[i]` as `*(a[i])` (so `b.leaves[1].a` became
 * `*(b.leaves[1]).a`) and `*p` as `**p`. Field access on `(*p)` and
 * `(p + 1)` also lost its brackets. */

#define TA_FILE "__211_transpile.ktm"
#define TA_C    "__211_transpile.c"
#define TA_EXE  "__211_transpile.exe"
#define TA_OUT  "__211_transpile.out"
/* `timeout` is coreutils; without it the command just runs */
#define TA_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "

void cleanupTranspileArrTest(void) {
    proc.shell("rm -f " TA_FILE " " TA_C " " TA_EXE " " TA_OUT);
}

u8 *taSource =
"struct Leaf
{
  i64 a;
  i32 b;
};

struct Branch
{
  Leaf leaves[2];
  i64 nums[3];
  i32 grid[2][3];
  u8 name[8];
  Branch *next;
};

void main()
{
  Branch b;
  Branch *p = &b;
  Branch **pp = &p;
  i64 loc[4];
  i16 cube[2][3][4];
  i64 *q = loc;
  u8 *s = \"hey\";

  b.leaves[1].a = 5;
  b.leaves[0].b = 6;
  b.nums[2] = 7;
  b.grid[1][2] = 8;
  b.name[0] = 'h';
  b.name[1] = 0;
  cube[1][2][3] = 9;
  loc[3] = 10;
  p.leaves[1].b = 11;
  *q = 12;
  *(q + 1) = 13;
  (*p).nums[0] = 14;
  (*pp).nums[1] = 15;
  b.next = p;
  b.next.leaves[0].a = 16;
  printf(\"%d %d %d %d %s %d %d %d\\n\", b.leaves[1].a, b.leaves[0].b, b.nums[2],
    b.grid[1][2], b.name, cube[1][2][3], loc[3], p.leaves[1].b);
  printf(\"%d %d %d %d %d %c %d\\n\", loc[0], q[1], b.nums[0], (*pp).nums[1],
    p.next.leaves[0].a, *(s + 1), sizeof(Branch));
}
";

u8 *taExpected = "5 6 7 8 h 9 10 11\n12 13 14 15 16 e 96\n";

void main() {
    printf("Test - transpile array members: ");
    i64 correct = 0, tests = 0, res;
    u8 *code, *out;

    cleanupTranspileArrTest();
    fs.writeAll(TA_FILE, taSource, str.len(taSource), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);

    /* What the JIT prints, to compare the C program with */
    tests++;
    res = proc.shell(TA_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " TA_FILE " > " TA_OUT " 2>&1");
    out = fs.readAll(TA_OUT);
    if (res == 0 && out && !str.cmp(out, taExpected)) { correct++; }
    else if (out) { printf("jit: %s\n", out); }
    if (out) { mem.free(out); }

    res = proc.shell(TA_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -transpile " TA_FILE " > " TA_C " 2>/dev/null");
    code = fs.readAll(TA_C);
    tests++;
    if (res == 0 && code) { correct++; }
    if (!code) { code = str.dup(""); }

    /* Element counts, every dimension */
    tests++;
    if (libc.strstr(code, "Leaf leaves[2];") && libc.strstr(code, "long nums[3];") &&
            libc.strstr(code, "int grid[2][3];") && libc.strstr(code, "unsigned char name[8];") &&
            libc.strstr(code, "long loc[4];") && libc.strstr(code, "short cube[2][3][4];")) {
        correct++;
    }

    /* Indexing and field access */
    tests++;
    if (libc.strstr(code, "b.leaves[1].a = 5;") && libc.strstr(code, "b.grid[1][2] = 8;") &&
            libc.strstr(code, "cube[1][2][3] = 9;") && libc.strstr(code, "p->leaves[1].b = 11;") &&
            libc.strstr(code, "b.next->leaves[0].a = 16;")) {
        correct++;
    }

    /* Dereferences */
    tests++;
    if (libc.strstr(code, "*q = 12;") && libc.strstr(code, "q[1] = 13;") &&
            libc.strstr(code, "(*p).nums[0] = 14;") && libc.strstr(code, "(*pp)->nums[1] = 15;") &&
            !libc.strstr(code, "**q") && !libc.strstr(code, "*(")) {
        correct++;
    }

    /* The C compiles and prints what the JIT does (only with a C
     * compiler on the PATH) */
    tests++;
    res = proc.shell("command -v cc >/dev/null 2>&1");
    if (res != 0) {
        correct++;
    } else {
        res = proc.shell("cc -w -o " TA_EXE " " TA_C " -lm > " TA_OUT " 2>&1 && " TA_TIMEOUT "./" TA_EXE " > " TA_OUT " 2>&1");
        out = fs.readAll(TA_OUT);
        if (res == 0 && out && !str.cmp(out, taExpected)) {
            correct++;
        } else if (out) {
            printf("%s\n", out);
        }
        if (out) { mem.free(out); }
    }

    mem.free(code);
    cleanupTranspileArrTest();
    test.printResult(correct, tests);
    printf("====\n");
}
