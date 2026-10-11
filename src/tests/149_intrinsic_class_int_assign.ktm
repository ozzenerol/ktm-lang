import test;

import fs, libc, mem, proc, str;

/* An intrinsic class (`i64 class CDate`, `u16 class`, `i32 class`) is an
 * integer underneath: `i64 x = date;` or `x += date;` warned
 * "Incompatible types 'CDate' is not assignable to type 'i64'" (integer to
 * intrinsic class was already silent). A compound assignment
 * also put the warning on the previous `=` in the source - for `y += d;`
 * that was the `CDate d = 5;` line above it. */

#define IC_TEST_FILE "__149_intrinsic_class.ktm"
#define IC_TEST_OUT  "__149_intrinsic_class.out"

void cleanupIcTest(void) {
    fs.remove(IC_TEST_FILE);
    fs.remove(IC_TEST_OUT);
}

/* Runs `source` with the JIT; the combined stdout and stderr, or null. */
u8 *runKtm(u8 *source) {
    cleanupIcTest();
    if (!fs.writeAll(IC_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return null;
    }
    proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " IC_TEST_FILE " > " IC_TEST_OUT " 2>&1");
    u8 *out = fs.readAll(IC_TEST_OUT);
    cleanupIcTest();
    return out;
}

bool contains(u8 *out, u8 *want) {
    if (out && libc.strstr(out, want)) { return true; }
    printf("\nexpected `%s` in:\n%s\n", want, out);
    return false;
}

bool lacks(u8 *out, u8 *unwanted) {
    if (out && !libc.strstr(out, unwanted)) { return true; }
    printf("\nunexpected `%s` in:\n%s\n", unwanted, out);
    return false;
}

u8 *intrinsicSrc =
"import date;

u16 struct W { u8 lo, hi; };
i32 struct D { u16 a, b; };
struct S { date.Date d; i64 x; };

date.Date ret() { return 5; }
i64 back(date.Date d) { return d; }
void take(date.Date d) { printf(\"take %d\\n\", d); }

void main() {
  date.Date d = 5;
  d = 6;
  d += 1;
  i64 y = d;
  y = d;
  y += d;
  y = y - d;
  i64 arr[2];
  arr[1] = d;
  S s;
  s.d = 9;
  s.x = s.d;
  W w = 0x1234;
  u16 u = w;
  i64 wy = w;
  w = u + 1;
  D dd = 0x10002;
  i32 i = dd;
  dd = i + 1;
  d = w;
  w = d;
  take(3);
  printf(\"%d %d %d %d %x %x %x %x %x %d %d\\n\",
    d, y, arr[1], s.x, u, wy, w, i, dd, ret(), back(7));
}
";

u8 *whereSrc =
"import date;

struct P { i64 a, b; };
P g;
P *Mk(i64 a, i64 b, i64 c) { return &g; }
void main() {
  u8 *s = \"x\";
  date.Date d = 5;
  i64 y = 1;
  y += d;
  P p;
  i64 z = p;
  f64 f = d;
  y = 2; s =
    Mk(4, 5, 6);
  printf(\"%d\\n\", y);
}
";

i32 main() {
    i64 correct = 0, tests = 8;

    printf("Test - integer <-> intrinsic class assignment: ");

    u8 *out = runKtm(intrinsicSrc);
    /* No diagnostic at all, and the values come through. */
    if (lacks(out, "warning") && lacks(out, "Incompatible")) { correct++; }
    if (contains(out, "take 3\n4661 7 7 9 1234 1234 1235 10002 10003 5 7\n")) { correct++; }
    mem.free(out);

    out = runKtm(whereSrc);
    /* `y += d` is fine and no longer reported against `CDate d = 5;`. */
    if (lacks(out, "'CDate' is not assignable to type 'i64'") &&
            lacks(out, "CDate d = 5;")) { correct++; }
    /* A real class still can't become an integer (an error since test
     * 222)... */
    if (contains(out, "`i64 = P` is invalid")) { correct++; }
    if (contains(out, "i64 z = p;")) { correct++; }
    /* An intrinsic class converts to a float like an integer, silently. */
    if (lacks(out, "'CDate' is not assignable to type 'f64'")) { correct++; }
    /* ...and the warning is on the line with the `=`, from the `=`. */
    if (contains(out, "Incompatible types 'P *' is not assignable to type 'u8 *'")) { correct++; }
    if (contains(out, "y = 2; s =\n") && lacks(out, "Mk(4, 5, 6);\n")) { correct++; }
    mem.free(out);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
