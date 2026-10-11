import test;

import fs, libc, mem, proc, str;

/* A print statement, `"%d\n", x;`, compiles to a call to C's printf
 * where `%d` is a 32-bit int, so 5000000000 printed as 705032704. In
 * ktm `%d` is an i64: the format's integer conversions are now made
 * 64-bit and narrow integer arguments are widened to match. The
 * program is compiled both ahead of time and with the JIT and its
 * printed output compared. */

#define PRINT64_TEST_SRC "./ktm-76-print64.ktm"
#define PRINT64_TEST_BIN "./ktm-76-print64"
#define PRINT64_TEST_OUT "./ktm-76-print64.out"
/* A single literal: adjacent strings don't concatenate inside #define */
#define PRINT64_KTM "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

void cleanupPrint64Test(void) {
    proc.shell("rm -f " PRINT64_TEST_SRC " " PRINT64_TEST_BIN " " PRINT64_TEST_OUT);
}

/* The captured output of the last run must be exactly `expected` */
bool outputIs(u8 *expected) {
    u8 *out = fs.readAll(PRINT64_TEST_OUT);
    bool ok;
    if (!out) { return false; }
    ok = str.cmp(out, expected) == 0;
    if (!ok) { printf("got: '%s'\n", out); }
    mem.free(out);
    return ok;
}

u8 *print64Source =
"i16 retI16(i64 v) { return v; }
void main()
{
  i64 big = 5000000000;
  i64 m = -1;
  u8 u8_ = 200;
  i32 i32_ = -5;
  i16 i16_ = -7;
  bool b = true;
  printf(\"%d %i %u\\n\", big, -big, big);
  printf(\"%x %X %o\\n\", big, big, big);
  printf(\"%d %d %d %d %d\\n\", u8_, i32_, i16_, b, retI16(-70000));
  printf(\"%d %u %x\\n\", (i16)m, (u32)m, (u8)m);
  printf(\"[%12d] [%-12d] [%012d] [%08X] [%+d]\\n\", big, big, big, 0xABC, 7);
  printf(\"%ld %lld %lu\\n\", big, -big, big);
  printf(\"%.3f %s %c %%d %d\\n\", 3.14159, \"str\", 'Z', -1);
  printf(\"top %d\" \" level %d\\n\", big, big * 2);
}
";

u8 *print64Expected =
"5000000000 -5000000000 5000000000
12a05f200 12A05F200 45201371000
200 -5 -7 1 -4464
-1 4294967295 ff
[  5000000000] [5000000000  ] [005000000000] [00000ABC] [+7]
5000000000 -5000000000 5000000000
3.142 str Z %d -1
top 5000000000 level 10000000000
";

i32 main() {
    printf("Test - 64-bit print statements: ");
    i64 tests = 2;
    i64 correct = 0;

    cleanupPrint64Test();
    fs.writeAll(PRINT64_TEST_SRC, print64Source, str.len(print64Source),
                        libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);

    if (proc.shell(PRINT64_KTM "-jit " PRINT64_TEST_SRC " > " PRINT64_TEST_OUT " 2>&1") == 0 &&
            outputIs(print64Expected)) { correct++; }
    else { printf("Failed - JIT output\n"); }

    if (proc.shell(PRINT64_KTM PRINT64_TEST_SRC " -o " PRINT64_TEST_BIN " && "
                         PRINT64_TEST_BIN " > " PRINT64_TEST_OUT " 2>&1") == 0 &&
            outputIs(print64Expected)) { correct++; }
    else { printf("Failed - AOT output\n"); }

    cleanupPrint64Test();
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
