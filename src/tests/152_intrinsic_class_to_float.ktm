import test;

import date, fs, libc, mem, proc, str;

/* An intrinsic class (`i64 class CDate`, `u16 class`, `i32 class`) is an
 * integer underneath, but converting one to a float was a raw bit copy:
 * `CDate d = 7; f64 f = d;` gave 0.0 (with a warning), as did passing it to
 * an f64 parameter or returning it from an f64 function, and `d + 0.5`,
 * `d == 7.0` or `f = d` did not compile. A float into an intrinsic class
 * (`CDate d = 2.5;`) kept the double's bit pattern. Both now convert like
 * the base integer type does (sitofp / fptosi), without a warning. */

u16 struct W { u8 lo, hi; };
i32 struct N { i16 a, b; };
u64 struct UC { u32 a, b; };

f64 Id(f64 x) { return x; }
f32 id32(f32 x) { return x; }
f64 fromDate(date.Date d) { return d; }
date.Date toDate(f64 f) { return f; }
f64 sum3(i64 a, f64 b, f64 c) { return a + b + c; }

date.Date gD;
f64 gF;
date.Date gFromF;

#define IF_TEST_FILE "__152_intrinsic_float.ktm"
#define IF_TEST_OUT  "__152_intrinsic_float.out"

u8 *diagSrc =
"import date;

f64 Id(f64 x) { return x; }
f64 fromDate(date.Date d) { return d; }
date.Date toDate(f64 f) { return f; }
void main() {
  date.Date d = 7;
  f64 f = d;
  f = d;
  f += d;
  Id(d);
  date.Date e = 2.5;
  e = f;
  printf(\"%f %d %d\\n\", f + d, d < f, toDate(fromDate(d)));
}
";

/* Runs `source` with the JIT; the combined stdout and stderr, or null. */
u8 *runKtm(u8 *source) {
    fs.remove(IF_TEST_FILE);
    fs.remove(IF_TEST_OUT);
    if (!fs.writeAll(IF_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return null;
    }
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " IF_TEST_FILE " > " IF_TEST_OUT " 2>&1");
    u8 *out = fs.readAll(IF_TEST_OUT);
    fs.remove(IF_TEST_FILE);
    fs.remove(IF_TEST_OUT);
    return out;
}

i32 main() {
    gD = 9;
    gF = gD;
    gFromF = 2.5;
    i64 correct = 0, tests = 22;

    printf("Test - intrinsic class to and from a float: ");

    date.Date d = 7;
    f64 f = d;
    if (f == 7.0) { correct++; }
    f = 0;
    f = d;
    if (f == 7.0) { correct++; }
    f = 1.0;
    f += d;
    if (f == 8.0) { correct++; }
    f = 2.0;
    f *= d;
    if (f == 14.0) { correct++; }

    /* Arguments, returns, and an argument among other parameters. */
    if (Id(d) == 7.0 && id32(d) == 7.0) { correct++; }
    if (fromDate(d) == 7.0 && fromDate(-3) == -3.0) { correct++; }
    if (sum3(d, d, 0.5) == 14.5) { correct++; }

    /* Arithmetic and comparison with a float, on either side. */
    if (d + 0.5 == 7.5 && 0.5 * d == 3.5 && d / 2.0 == 3.5) { correct++; }
    if (d == 7.0 && d < 7.5 && 6.5 < d && !(d > 7.5)) { correct++; }
    if ((f64)d == 7.0) { correct++; }

    /* Signed and unsigned bases, narrow and wide. */
    date.Date neg = -3;
    f = neg;
    if (f == -3.0) { correct++; }
    W w = 65535;
    N n = -5;
    f64 fw = w, fn_ = n;
    if (fw == 65535.0 && fn_ == -5.0) { correct++; }
    UC uc = 0xFFFFFFFFFFFFFFFF;
    f = uc;
    if (f == 18446744073709551615.0) { correct++; }
    f32 h = d;
    if (h == 7.0) { correct++; }

    /* Globals. */
    if (gF == 9.0 && gFromF == 2) { correct++; }

    /* Float into an intrinsic class truncates like an integer. */
    date.Date e = 3.9;
    if (e == 3) { correct++; }
    e = -4.9;
    if (e == -4) { correct++; }
    if (toDate(5.7) == 5 && toDate(fromDate(d)) == 7) { correct++; }
    e = 7;
    e += 1.5;
    if (e == 8) { correct++; }
    W w2 = 300.7;
    if (w2 == 300) { correct++; }

    /* No diagnostics, and the right values from the JIT too. */
    u8 *out = runKtm(diagSrc);
    if (out && !libc.strstr(out, "warning") && !libc.strstr(out, "error")) { correct++; }
    else { printf("\nunexpected diagnostics:\n%s\n", out); }
    if (out && libc.strstr(out, "21.000000 1 7\n")) { correct++; }
    else { printf("\nunexpected output:\n%s\n", out); }
    mem.free(out);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
