import fs, libc, mem, proc, str, test;

#define ARG_COUNT_TEST_FILE "__39_arg_count_fail.ktm"
#define ARG_COUNT_TEST_OUT  "__39_arg_count_fail.out"

void cleanupArgCountTest(void) {
    fs.remove(ARG_COUNT_TEST_FILE);
    fs.remove(ARG_COUNT_TEST_OUT);
    fs.remove("./a.out");
}

bool compileFailsWith(u8 *source, u8 *expected) {
    u8 *out;
    i64 res;

    cleanupArgCountTest();

    if (!fs.writeAll(ARG_COUNT_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " ARG_COUNT_TEST_FILE " > " ARG_COUNT_TEST_OUT " 2>&1");
    if (res == 0) {
        cleanupArgCountTest();
        return false;
    }

    out = fs.readAll(ARG_COUNT_TEST_OUT);
    if (!out) {
        cleanupArgCountTest();
        return false;
    }

    if (!libc.strstr(out, expected)) {
        printf("%s\n", out);
        mem.free(out);
        cleanupArgCountTest();
        return false;
    }

    mem.free(out);
    cleanupArgCountTest();
    return true;
}

bool testOverArgs(void) {
    return compileFailsWith(
"
void DoNothing(i32 a, i32 b) {
  return;
}

void main() {
  DoNothing(1,2,3,4);
}
",
        "Unexpected number of arguments 4 in call to DoNothing(), expected 2 args");
}

bool testUnderArgs(void) {
    return compileFailsWith(
"
void DoNothing(i32 a, i32 b) {
  return;
}

void main() {
  DoNothing(1);
}
",
        "Unexpected number of arguments 1 in call to DoNothing(), expected 2 args");
}

bool testOverDefaultArgs(void) {
    return compileFailsWith(
"
i64 AddS(i64 i1,
         i64 i2=2,
         i64 i3)
{
  return i1+i2+i3;
}

void main()
{
  i64 res = AddS(3,4,5,6);
}
",
        "Unexpected number of arguments 4 in call to AddS(), expected args in range 2 to 3");
}

bool testUnderDefaultArgs(void) {
    return compileFailsWith(
"
i64 AddS(i64 i1,
         i64 i2=2,
         i64 i3)
{
  return i1+i2+i3;
}

void main()
{
  i64 res = AddS(3);
}
",
        "Unexpected number of arguments 1 in call to AddS(), argument 3 is required");
}

bool testMidDefaultMissingRequiredArg(void) {
    return compileFailsWith(
"
i64 AddS(i64 i1, i64 i2=2, i64 i3)
{
  return i1+i2+i3;
}

void main()
{
  AddS(1,2);
}
",
        "Unexpected number of arguments 2 in call to AddS(), argument 3 is required");
}

bool testUnderVarArgs(void) {
    return compileFailsWith(
"
i64 AddS(i64 i1, ...)
{
  return i1;
}

void main()
{
  AddS();
}
",
        "Unexpected number of arguments 0 in call to AddS(), expected 1 args");
}

bool testUnderFunctionPointerArgs(void) {
    return compileFailsWith(
"
i64 Add(i64 i1, i64 i2)
{
  return i1+i2;
}

void main()
{
  fn(i64 i1, i64 i2) i64 f = &Add;
  f(1);
}
",
        "Unexpected number of arguments 1 in call to f(), expected 2 args");
}

i32 main() {
    printf("Test - Argument count errors: \n");
    i64 tests = 7;
    i64 correct = 0;

    if (testOverArgs()) { correct++; }
    else { printf("Failed - TestOverArgs()\n"); }

    if (testUnderArgs()) { correct++; }
    else { printf("Failed - TestUnderArgs()\n"); }

    if (testOverDefaultArgs()) { correct++; }
    else { printf("Failed - TestOverDefaultArgs()\n"); }

    if (testUnderDefaultArgs()) { correct++; }
    else { printf("Failed - TestUnderDefaultArgs()\n"); }

    if (testMidDefaultMissingRequiredArg()) { correct++; }
    else { printf("Failed - TestMidDefaultMissingRequiredArg()\n"); }

    if (testUnderVarArgs()) { correct++; }
    else { printf("Failed - TestUnderVarArgs()\n"); }

    if (testUnderFunctionPointerArgs()) { correct++; }
    else { printf("Failed - TestUnderFunctionPointerArgs()\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
