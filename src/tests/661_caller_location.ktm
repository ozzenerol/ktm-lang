import cstr, fs, libc, mem, proc, test;

/* @caller parameters (v0.15.0): @caller("file"), ("line") and ("func")
 * are filled in by every call with where it is written; callers never
 * pass them. They work before a format's ...args, in methods, next to
 * defaults and through a function pointer; misuse is a compile error. */

#define CALLER_TEST_FILE "__661_caller_fail.ktm"
#define CALLER_TEST_OUT  "__661_caller_fail.out"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

u8 gLast[256];

u8 *baseName(u8 *path) {
    u8 *slash = libc.strrchr(path, '/');
    return slash ? slash + 1 : path;
}

void where(@caller("file") u8 *file, @caller("line") i64 line,
           @caller("func") u8 *func, u8 *fmt, ...args)
{
    u8 *msg = cstr.formatArgs(null, fmt, args);
    libc.snprintf(gLast, 256, "%s|%d|%s|%s", baseName(file), line, func, msg);
    mem.free(msg);
}

struct Counter { i64 n; };

i64 Counter.bump(@caller("line") i64 line, i64 by = 1)
{
    self.n += by;
    return line;
}

i64 lineOf(@caller("line") i64 line) { return line; }

u8 *funcOf(@caller("func") u8 *func) { return func; }

i64 trailing(i64 x, i64 y = 5, @caller("line") i64 line)
{
    return x * 100000 + y * 1000 + line;
}

u8 *fromHelper() { return funcOf(); }

bool compileFailsWith(u8 *source, u8 *expected) {
    fs.remove(CALLER_TEST_FILE);
    fs.remove(CALLER_TEST_OUT);
    if (!fs.writeAll(CALLER_TEST_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    i64 res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -o __661_caller_fail " CALLER_TEST_FILE " > " CALLER_TEST_OUT " 2>&1");
    u8 *out = fs.readAll(CALLER_TEST_OUT).take();
    bool ok = res != 0 && out && libc.strstr(out, expected);
    if (!ok && out) { printf("\n%s", out); }
    mem.free(out);
    fs.remove(CALLER_TEST_FILE);
    fs.remove(CALLER_TEST_OUT);
    fs.remove("__661_caller_fail");
    return ok;
}

i32 main()
{
    printf("Test - @caller parameters: ");

    i64 here = lineOf();
    check(lineOf() == here + 1, "the line is the call's");
    check(!libc.strcmp(funcOf(), "main"), "main is named main");
    check(!libc.strcmp(fromHelper(), "fromHelper"), "the function the call is in");

    where("hello %d", 42); i64 at = lineOf();
    u8 want[256];
    libc.snprintf(want, 256, "661_caller_location.ktm|%d|main|hello 42", at);
    check(!libc.strcmp(gLast, want), "file, line and func before ...args");
    where("no extras");
    check(libc.strstr(gLast, "|main|no extras") != null, "no extras");

    Counter c;
    c.n = 0;
    i64 l1 = c.bump(); i64 l2 = c.bump(10); i64 lm = lineOf();
    check(c.n == 11, "a method's own arguments");
    check(l1 == lm && l2 == lm, "a method call's line");

    i64 t = trailing(1); i64 tl = lineOf();
    check(t == 100000 + 5000 + tl, "after a default left out");
    t = trailing(2, 3); tl = lineOf();
    check(t == 200000 + 3000 + tl, "after a default written");

    auto p = &lineOf;
    check(p() == lineOf(), "through an auto function pointer");
    fn(i64) i64 q = &lineOf;
    check(q(7) == 7, "a fn type passes it explicitly");

    check(compileFailsWith("void f(@caller(\"line\") u8 *x) {}\nvoid main() {}\n",
                           "`@caller(\"line\")` goes on an integer parameter, not a u8 *"),
          "line on a u8 *");
    check(compileFailsWith("void f(@caller(\"file\") i64 x) {}\nvoid main() {}\n",
                           "`@caller(\"file\")` goes on a u8 * parameter, not a i64"),
          "file on an i64");
    check(compileFailsWith("void f(@caller(\"col\") i64 x) {}\nvoid main() {}\n",
                           "`@caller` takes \"file\", \"line\" or \"func\""),
          "an unknown @caller kind");
    check(compileFailsWith("void f(@json i64 x) {}\nvoid main() {}\n",
                           "a parameter takes one attribute, @caller"),
          "another attribute on a parameter");
    check(compileFailsWith("void f(@caller(\"line\") i64 x = 3) {}\nvoid main() {}\n",
                           "a `@caller` parameter has no default"),
          "a default on a @caller parameter");
    check(compileFailsWith("void f(@caller(\"line\") i64 x, u8 *a) {}\nvoid f(@caller(\"line\") i64 x, i64 a) {}\nvoid main() {}\n",
                           "`f` has a @caller parameter, so it can't be overloaded"),
          "overloading");
    check(compileFailsWith("void f(@caller(\"line\") i64 x, u8 *a) {}\nvoid main() { f(); }\n",
                           "Unexpected number of arguments 0 in call to f(), expected 1 args"),
          "the count leaves @caller out");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
