import cstr, date, fs, libc, mem, proc, test;

/* A nested `{ ... }` in an array initialiser must initialise an element
 * that is an array, a class or a union. For a scalar element
 * (`i64 a[2] = {1, {2}};`) it used to be accepted silently and the
 * element was given the list's address. And an array initialised from
 * something other than a list (`i64 a[2] = 5;`) printed the token's
 * value as a character, e.g. "got: '\x05'", instead of the token.
 *
 * Unlike C, a scalar can't be initialised from a braced list either
 * (`i64 x = {1};`): it's rejected, and reported once (the recovery used
 * to stop inside the list and report its `}` as a second error). */

#define SB_TEST_FILE "__153_brace_list_scalar_element.ktm"
#define SB_TEST_OUT  "__153_brace_list_scalar_element.out"

#define MSG_ELEM_I64 "Cannot use an initialiser list for an element of type i64"
#define MSG_EXPECTED "Expected initializer list starting with '{', got "

void cleanupScalarBraceTest(void) {
    fs.remove(SB_TEST_FILE);
    fs.remove(SB_TEST_OUT);
    fs.remove("./a.out");
}

i64 countErrors(u8 *out) {
    i64 n = 0;
    u8 *p = out;
    while ((p = libc.strstr(p, "error: "))) {
        n++;
        p += 7;
    }
    return n;
}

/* Compile `source`: ktm must exit with status 1 having printed `errors`
 * errors, the first being `expected` at `where`. */
bool compileFailsWith(u8 *source, u8 *expected, u8 *where, i64 errors=1) {
    u8 *out, *err;
    i64 res;
    bool ok;

    cleanupScalarBraceTest();

    if (!fs.writeAll(SB_TEST_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " SB_TEST_FILE " > " SB_TEST_OUT " 2>&1");

    out = fs.readAll(SB_TEST_OUT).take();
    if (!out) {
        printf("status %d, no output\n", res);
        cleanupScalarBraceTest();
        return false;
    }

    ok = res == 1 && countErrors(out) == errors;
    err = libc.strstr(out, "error: ");
    if (!err || cstr.cmpLen(err + 7, expected, cstr.len(expected))) { ok = false; }
    if (!libc.strstr(out, where)) { ok = false; }
    if (!ok) { printf("status %d\n%s\n", res, out); }

    mem.free(out);
    cleanupScalarBraceTest();
    return ok;
}

bool testScalarElement(void) {
    return compileFailsWith(
"void main()
{
  i64 a[2] = {1, {2}};
}
", MSG_ELEM_I64, SB_TEST_FILE ":3:18");
}

bool testEveryElementBraced(void) {
    return compileFailsWith(
"void main()
{
  i64 a[2] = {{1}, {2}};
}
", MSG_ELEM_I64, SB_TEST_FILE ":3:15");
}

bool testInnerDimension(void) {
    return compileFailsWith(
"void main()
{
  i64 a[2][2] = {{1, 2}, {3, {4}}};
}
", MSG_ELEM_I64, SB_TEST_FILE ":3:30");
}

bool testGlobal(void) {
    return compileFailsWith(
"i64 g[2] = {1, {2}};
void main() {}
", MSG_ELEM_I64, SB_TEST_FILE ":1:16");
}

bool testOtherScalars(void) {
    bool ok = true;
    if (!compileFailsWith(
"void main()
{
  f64 f[2] = {1.0, {2.0}};
}
", "Cannot use an initialiser list for an element of type f64",
            SB_TEST_FILE ":3:20")) { ok = false; }
    if (!compileFailsWith(
"void main()
{
  i64 *p[2] = {{1}, {2}};
}
", "Cannot use an initialiser list for an element of type i64 *",
            SB_TEST_FILE ":3:16")) { ok = false; }
    /* An intrinsic class is a scalar */
    if (!compileFailsWith(
"import date;

void main()
{
  date.Date d[2] = {1, {2}};
}
", "Cannot use an initialiser list for an element of type date.Date",
            SB_TEST_FILE ":5:24")) { ok = false; }
    return ok;
}

bool testClassScalarField(void) {
    return compileFailsWith(
"struct C { i64 a, b; };
void main()
{
  C c = {1, {2}};
}
", "Cannot use an initialiser list for field b of type i64",
        SB_TEST_FILE ":4:13");
}

bool testBracedScalar(void) {
    bool ok = true;
    if (!compileFailsWith(
"void main()
{
  i64 x = {1};
}
", "Cannot use an initialiser list for a variable of type i64",
            SB_TEST_FILE ":3:11")) { ok = false; }
    if (!compileFailsWith(
"import date;

void main()
{
  date.Date d = {1, {2}};
  i64 *p = {};
}
", "Cannot use an initialiser list for a variable of type date.Date",
            SB_TEST_FILE ":5:17", 2)) { ok = false; }
    /* At file scope it's read as an expression */
    if (!compileFailsWith(
"i64 g = {1};
void main() {}
", "Second operand missing to `g =` got invalid character `{`",
            SB_TEST_FILE ":1:7")) { ok = false; }
    return ok;
}

bool testExpectedList(void) {
    bool ok = true;
    if (!compileFailsWith(
"void main()
{
  i64 a[2] = 5;
}
", MSG_EXPECTED "`5`", SB_TEST_FILE ":3:14")) { ok = false; }
    if (!compileFailsWith(
"void main()
{
  i64 n = 1;
  i64 a[2] = n;
}
", MSG_EXPECTED "`n`", SB_TEST_FILE ":4:14")) { ok = false; }
    /* Points at the `;`, not at the next line */
    if (!compileFailsWith(
"void main()
{
  i64 a[2] = ;
  i64 b = 1;
}
", MSG_EXPECTED "`;`", SB_TEST_FILE ":3:14")) { ok = false; }
    return ok;
}

/* Each bad initialiser is reported once and the parser carries on */
bool testRecoveryContinues(void) {
    return compileFailsWith(
"i64 g[2] = 5;
void main()
{
  i64 a[2] = {1, {2}};
  i64 b[2][2] = {{1, 2}, {3, {4}}};
  i64 c[2] = ;
  i64 x = undefined_thing;
}
", MSG_EXPECTED "`5`", SB_TEST_FILE ":1:12", 5);
}

/* Valid nested initialisers keep working */
struct SbPair {
    i64 a, b;
};

struct SbRow {
    i64 n;
    i64 v[3];
};

union SbNum {
    i64 i;
    f64 f;
};

SbPair gPairs[2] = {{1, 2}, {3}};
i64 gGrid[2][2] = {{5, 6}, {7}};

bool testValid(void) {
    i64 grid[2][3] = {{1, 2, 3}, {4}};
    SbPair pairs[2] = {{1, 2}, {3, 4}};
    SbRow row = {9, {7, 8}};
    SbNum nums[2] = {{11}, {12}};
    u8 strs[2][4] = {"ab", "cd"};
    f64 fs[2] = {1.5, 2.5};
    date.Date ds[2] = {1, 2};
    bool ok = true;

    if (grid[0][2] != 3 || grid[1][0] != 4 || grid[1][1] != 0) { ok = false; }
    if (pairs[1].a != 3 || pairs[1].b != 4) { ok = false; }
    if (row.n != 9 || row.v[0] != 7 || row.v[1] != 8 || row.v[2] != 0) { ok = false; }
    if (nums[0].i != 11 || nums[1].i != 12) { ok = false; }
    if (cstr.cmp(strs[1], "cd")) { ok = false; }
    if (fs[1] != 2.5 || ds[1] != 2) { ok = false; }
    if (gPairs[0].b != 2 || gPairs[1].a != 3 || gPairs[1].b != 0) { ok = false; }
    if (gGrid[0][1] != 6 || gGrid[1][0] != 7 || gGrid[1][1] != 0) { ok = false; }
    return ok;
}

i32 main() {
    printf("Test - brace list for a scalar element: ");
    i64 correct = 0, tests = 0;

    tests++; if (testScalarElement())      { correct++; }
    tests++; if (testEveryElementBraced()) { correct++; }
    tests++; if (testInnerDimension())     { correct++; }
    tests++; if (testGlobal())             { correct++; }
    tests++; if (testOtherScalars())       { correct++; }
    tests++; if (testClassScalarField())   { correct++; }
    tests++; if (testBracedScalar())       { correct++; }
    tests++; if (testExpectedList())       { correct++; }
    tests++; if (testRecoveryContinues())  { correct++; }
    tests++; if (testValid())              { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
