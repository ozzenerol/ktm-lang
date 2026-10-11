import test;

import fs, libc, mem, proc, str;

/* `-transpile` wrote the classes first and the unions after them, so a
 * class holding a named union by value used it before its typedef, and
 * it turned f32 into double. Each class or union is now written after
 * the ones it holds by value (members, array members, members of an
 * anonymous nested union), and f32 is float. */

#define TR_FILE "__199_transpile.ktm"
#define TR_C    "__199_transpile.c"
#define TR_EXE  "__199_transpile.exe"
#define TR_OUT  "__199_transpile.out"
/* `timeout` is coreutils; without it the command just runs */
#define TR_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "

void cleanupTranspileTest(void) {
    proc.shell("rm -f " TR_FILE " " TR_C " " TR_EXE " " TR_OUT);
}

u8 *trSource =
"struct Zeta
{
  f32 z;
  i32 pad;
};

union Word
{
  i64 i;
  f64 f;
  Zeta zeta;
};

struct Yak
{
  Zeta zeta;
  Word word;
  f32 scale;
};

struct Apple
{
  Yak yak;
  union {
    Word inner;
    i64 raw;
  };
  Apple *next;
};

union Holder
{
  Apple apple;
  i64 tag;
};

struct Base
{
  Holder holder;
  Yak yaks[2];
  f32 ratio;
};

void main()
{
  Base b;
  b.holder.apple.yak.zeta.z = 2.5;
  b.holder.apple.yak.word.zeta.pad = 4;
  b.holder.apple.inner.i = 9;
  b.holder.apple.next = null;
  b.ratio = 0.25;
  printf(\"%.2f %d %d %.2f\\n\", b.holder.apple.yak.zeta.z,
    b.holder.apple.yak.word.zeta.pad, b.holder.apple.inner.i, b.ratio);
}
";

/* `first` appears in `code`, and before `second` */
bool before(u8 *code, u8 *first, u8 *second) {
    u8 *a = libc.strstr(code, first), *b = libc.strstr(code, second);
    if (!a || !b || a > b) {
        printf("\"%s\" is not before \"%s\"\n", first, second);
        return false;
    }
    return true;
}

void main() {
    printf("Test - transpile nested class order: ");
    i64 correct = 0, tests = 0, res;
    u8 *code, *out;

    cleanupTranspileTest();
    fs.writeAll(TR_FILE, trSource, str.len(trSource), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    res = proc.shell(TR_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -transpile " TR_FILE " > " TR_C " 2>/dev/null");
    code = fs.readAll(TR_C);

    tests++;
    if (res == 0 && code) { correct++; }
    if (!code) { code = str.dup(""); }

    /* Every by-value dependency comes first, through unions, arrays and
     * an anonymous union */
    tests++;
    if (before(code, "typedef struct Zeta {", "typedef union Word {") &&
            before(code, "typedef union Word {", "typedef struct Yak {") &&
            before(code, "typedef struct Yak {", "typedef struct Apple {") &&
            before(code, "typedef struct Apple {", "typedef union Holder {") &&
            before(code, "typedef union Holder {", "typedef struct Base {")) {
        correct++;
    }

    /* Each written once */
    tests++;
    u8 *w = libc.strstr(code, "typedef union Word {");
    if (w && !libc.strstr(w + 1, "typedef union Word {")) { correct++; }

    /* f32 is float, f64 double */
    tests++;
    if (libc.strstr(code, "float z;") && libc.strstr(code, "float scale;") &&
            libc.strstr(code, "float ratio;") && libc.strstr(code, "double f;")) {
        correct++;
    }

    /* A pointer to the class being written still uses its tag */
    tests++;
    if (libc.strstr(code, "struct Apple *next;")) { correct++; }

    /* The C compiles and prints what the JIT does (only with a C
     * compiler on the PATH) */
    tests++;
    res = proc.shell("command -v cc >/dev/null 2>&1");
    if (res != 0) {
        correct++;
    } else {
        res = proc.shell("cc -w -o " TR_EXE " " TR_C " -lm > " TR_OUT " 2>&1 && " TR_TIMEOUT "./" TR_EXE " > " TR_OUT " 2>&1");
        out = fs.readAll(TR_OUT);
        if (res == 0 && out && !str.cmp(out, "2.50 4 9 0.25\n")) {
            correct++;
        } else if (out) {
            printf("%s\n", out);
        }
        if (out) { mem.free(out); }
    }

    mem.free(code);
    cleanupTranspileTest();
    test.printResult(correct, tests);
    printf("====\n");
}
