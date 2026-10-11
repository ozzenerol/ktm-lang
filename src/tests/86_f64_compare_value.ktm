import cstr, test;

/* A float comparison used as a value (not a branch condition) was
 * typed as f64 by the parser, so `i64 x = b < 3.0;` converted the 0/1
 * result as if it were a double and always produced 0. Comparisons
 * involving NaN must also follow C: only `!=` is true. Every check
 * below compares the materialised value as an integer, so the test
 * itself never relies on a float comparison being a branch. */

i64 gCorrect = 0;

void expect(i64 got, i64 want) {
    if (got == want) { gCorrect++; }
}

bool isTwo(f64 x) { return x == 2.0; }
i64 less(f64 a, f64 c) { return a < c; }
i64 idFn(i64 v) { return v; }

i32 main() {
    i64 tests = 77;

    printf("Test - f64 comparison used as a value: ");

    f64 b = 2.0, c = 3.0, z = 0.0;
    f64 nan = z / z;
    f32 f = 2.0;
    i64 v;

    /* The original report. */
    i64 eq = b == 2.0, lt = b < 3.0, ne = b != 1.0, gt = b > 1.0;
    bool both = 1 == 1 && b == 2.0;
    expect(eq, 1); expect(lt, 1); expect(ne, 1); expect(gt, 1);
    expect(both, 1);

    /* Every operator, both operand orders, f64 vs f64 variables. */
    v = b == c; expect(v, 0);  v = b != c; expect(v, 1);
    v = b < c;  expect(v, 1);  v = b <= c; expect(v, 1);
    v = b > c;  expect(v, 0);  v = b >= c; expect(v, 0);
    v = c == b; expect(v, 0);  v = c != b; expect(v, 1);
    v = c < b;  expect(v, 0);  v = c <= b; expect(v, 0);
    v = c > b;  expect(v, 1);  v = c >= b; expect(v, 1);
    v = b == b; expect(v, 1);  v = b != b; expect(v, 0);
    v = b < b;  expect(v, 0);  v = b <= b; expect(v, 1);
    v = b > b;  expect(v, 0);  v = b >= b; expect(v, 1);

    /* Literal on either side. */
    v = 2.0 == b; expect(v, 1);  v = 2.0 != b; expect(v, 0);
    v = 1.0 < b;  expect(v, 1);  v = 3.0 <= b; expect(v, 0);
    v = 3.0 > b;  expect(v, 1);  v = 2.0 >= b; expect(v, 1);

    /* f32 and mixed f64/i64. */
    v = f == 2.0; expect(v, 1);  v = f != 2.0; expect(v, 0);
    v = f < 3.0;  expect(v, 1);  v = f <= 1.0; expect(v, 0);
    v = f > 1.0;  expect(v, 1);  v = f >= 3.0; expect(v, 0);
    v = b == 2;   expect(v, 1);  v = b != 2;   expect(v, 0);
    v = b < 3;    expect(v, 1);  v = 2 == b;   expect(v, 1);

    /* NaN: unordered, so everything but != is false. */
    v = nan == nan; expect(v, 0);  v = nan != nan; expect(v, 1);
    v = nan < b;    expect(v, 0);  v = nan <= b;   expect(v, 0);
    v = nan > b;    expect(v, 0);  v = nan >= b;   expect(v, 0);
    v = b == nan;   expect(v, 0);  v = b != nan;   expect(v, 1);
    v = b < nan;    expect(v, 0);  v = b <= nan;   expect(v, 0);
    v = b > nan;    expect(v, 0);  v = b >= nan;   expect(v, 0);
    v = 0; if (nan == nan) { v = 1; } expect(v, 0);
    v = 0; if (nan != nan) { v = 1; } expect(v, 1);
    v = 0; if (nan < b) { v = 1; }    expect(v, 0);
    v = 0; if (nan <= b) { v = 1; }   expect(v, 0);
    v = 0; if (b > nan) { v = 1; }    expect(v, 0);
    v = 0; if (b >= nan) { v = 1; }   expect(v, 0);

    /* Negation and && / || chains. */
    v = !(b == 2.0); expect(v, 0);
    v = !(b != 2.0); expect(v, 1);
    v = !(nan == nan); expect(v, 1);
    v = b < 1.0 || b > 1.0;     expect(v, 1);
    v = b < 1.0 || nan == nan;  expect(v, 0);
    v = b == 2.0 && c == 3.0;   expect(v, 1);
    v = b == 2.0 && c < 3.0;    expect(v, 0);

    /* Function arguments and return values. */
    expect(b < c, 1);
    expect(idFn(b > 1.0), 1);
    expect(isTwo(2.0), 1);
    expect(isTwo(3.0), 0);
    expect(less(b, c), 1);
    expect(less(c, b), 0);

    /* Stored into bool / u8, and used in arithmetic. */
    bool bb = b == 2.0;
    u8 u = b < c;
    expect(bb, 1);
    expect(u, 1);
    i64 n = 0;
    n += b > 1.0;
    n += c > 1.0;
    n += nan > 1.0;
    expect(n, 2);
    v = (b < c) + (c > b) * 10;
    expect(v, 11);

    /* Range comparison. */
    v = 1.0 < b < 3.0; expect(v, 1);
    v = 1.0 < b < 1.5; expect(v, 0);

    /* Printed directly. */
    u8 buf[32];
    cstr.formatTo(buf, "%d%d%d", b == 2.0, b < 1.0, nan != nan);
    expect(cstr.cmp(buf, "101"), 0);

    test.printResult(gCorrect, tests);
    printf("====\n");
    return 0;
}
