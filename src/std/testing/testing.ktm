module testing;
import cstr, date, mem, os, str;

/* Tests (v0.21.0), as Go's testing package, run by `ktm test`:
 *
 *   // store/store_test.ktm: next to the code, in its module
 *   module store;
 *   import testing;
 *
 *   void testAdd(testing.T *t) {
 *       t.equal(add(2, 3), 5);
 *       t.check(count() > 0, "count is %d", count());
 *   }
 *
 * `ktm test` finds the *_test.ktm files and the void testXxx(testing.T *t)
 * functions in them, and runs each in turn. A failed check, equal or
 * fail marks the test failed and it goes on; fatal and skip end it. Its
 * messages (log's too) are printed when it fails, or always with -v. */

/* What ends a test early (thrown, caught by the runner) */
enum Stop { fatal = 0x7e57, skip };

pub struct T {
    u8 *name;               /* the test's, as "testAdd" */
    private bool failed;
    private bool skipped;
    private bool verbose;
    private String out;     /* its messages */
};

static u8 *baseName(u8 *path) {
    if (!path) { return "?"; }
    u8 *base = path;
    for (u8 *p = path; *p; p++) {
        if (*p == '/') { base = p + 1; }
    }
    return base;
}

/* "    file.ktm:12: message\n" into the test's messages */
static void note(T *t, u8 *file, i64 line, u8 *msg) {
    t.out.appendFormat("    %s:%d: %s\n", baseName(file), line, msg);
}

static void failWith(T *t, u8 *file, i64 line, u8 *msg) {
    t.failed = true;
    note(t, file, line, msg);
    mem.free(msg);
}

/* Fails the test with printf's format and arguments when ok is false */
pub void T.check(@caller("file") u8 *file, @caller("line") i64 line, bool ok,
                 u8 *fmt = "check failed", ...args)
{
    if (ok) { return; }
    failWith(self, file, line, cstr.formatArgs(null, fmt, args));
}

static void notEqual(T *t, u8 *file, i64 line, String *msg) {
    failWith(t, file, line, cstr.dup(msg.cstr()));
    msg.free();
}

static void quoted(String *s, u8[] v) {
    s.appendFormat("\"%s\"", v);
}

/* got == want, or the test fails with both: integers (bools, chars and
 * enums too: a comparison is an i64), floats, text (u8 *, u8[], String: equal bytes; null only
 * equals null) */
pub void T.equal(@caller("file") u8 *file, @caller("line") i64 line, i64 got, i64 want) {
    if (got == want) { return; }
    String msg = str.format("got %d, want %d", got, want);
    notEqual(self, file, line, &msg);
}

pub void T.equal(@caller("file") u8 *file, @caller("line") i64 line, f64 got, f64 want) {
    if (got == want) { return; }
    String msg = str.format("got %f, want %f", got, want);
    notEqual(self, file, line, &msg);
}

pub void T.equal(@caller("file") u8 *file, @caller("line") i64 line, u8[] got, u8[] want) {
    if (str.equals(got, want)) { return; }
    String msg = str.from("got ");
    quoted(&msg, got);
    msg.append(", want ");
    quoted(&msg, want);
    notEqual(self, file, line, &msg);
}

pub void T.equal(@caller("file") u8 *file, @caller("line") i64 line, u8 *got, u8 *want) {
    if (got == want || (got && want && !cstr.cmp(got, want))) { return; }
    String msg = str.from("got ");
    if (got) { quoted(&msg, cstr.view(got)); } else { msg.append("null"); }
    msg.append(", want ");
    if (want) { quoted(&msg, cstr.view(want)); } else { msg.append("null"); }
    notEqual(self, file, line, &msg);
}

pub void T.equal(@caller("file") u8 *file, @caller("line") i64 line, String got, u8[] want) {
    if (str.equals(got[0:], want)) { return; }
    String msg = str.from("got ");
    quoted(&msg, got[0:]);
    msg.append(", want ");
    quoted(&msg, want);
    notEqual(self, file, line, &msg);
}

/* Fails the test with a message; it goes on */
pub void T.fail(@caller("file") u8 *file, @caller("line") i64 line, u8 *fmt, ...args) {
    failWith(self, file, line, cstr.formatArgs(null, fmt, args));
}

/* Fails the test with a message and ends it */
pub void T.fatal(@caller("file") u8 *file, @caller("line") i64 line, u8 *fmt, ...args) {
    failWith(self, file, line, cstr.formatArgs(null, fmt, args));
    throw(Stop.fatal);
}

/* Ends the test, counted as skipped (not failed), with why */
pub void T.skip(@caller("file") u8 *file, @caller("line") i64 line, u8 *fmt, ...args) {
    u8 *msg = cstr.formatArgs(null, fmt, args);
    note(self, file, line, msg);
    mem.free(msg);
    self.skipped = true;
    throw(Stop.skip);
}

/* A message, shown when the test fails or with -v */
pub void T.log(@caller("file") u8 *file, @caller("line") i64 line, u8 *fmt, ...args) {
    u8 *msg = cstr.formatArgs(null, fmt, args);
    note(self, file, line, msg);
    mem.free(msg);
}

/* Has it failed so far? */
pub bool T.hasFailed() {
    return self.failed;
}

/* ---- the runner: the main() `ktm test` writes calls these ---- */

pub struct M {
    private u8 *label;      /* what is tested, for the summary line */
    private u8 *filter;     /* -run: tests whose name contains it */
    private bool verbose;
    private i64 passed, failed, skipped;
    private u64 started;
};

static void usage() {
    printf("usage: ktm test [-v] [-run name] [-jit] [dir | ./...]\n");
    os.exit(2);
}

/* The runner's arguments: the label, then -v and -run <substring> */
pub M *start(i32 argc, u8 **argv) {
    M *m = mem.allocZero(sizeof(M));
    m.label = argc > 1 ? argv[1] : ".";
    for (i64 i = 2; i < argc; i++) {
        if (!cstr.cmp(argv[i], "-v")) { m.verbose = true; }
        else if (!cstr.cmp(argv[i], "-run") && i + 1 < argc) { m.filter = argv[++i]; }
        else { usage(); }
    }
    m.started = date.nowMs();
    return m;
}

static void seconds(u64 ms) {
    printf("%d.%02ds", ms / 1000, (ms % 1000) / 10);
}

/* Runs test fn called name (unless -run leaves it out) */
pub void M.run(u8 *name, fn(T *) void test) {
    if (self.filter && !str.contains(cstr.view(name), cstr.view(self.filter))) { return; }
    T t;    /* zeroed, as every local */
    t.name = name;
    t.verbose = self.verbose;
    t.out = str.new();
    if (self.verbose) { printf("=== RUN   %s\n", name); os.flush(); }
    u64 began = date.nowMs();
    try {
        test(&t);
    } catch (Error e) {
        if (e.code != Stop.fatal && e.code != Stop.skip) {
            t.failed = true;
            t.out.appendFormat("    %s:%d: threw %d: %s\n", baseName(e.file), e.line, e.code, e.message);
        }
    }
    u64 took = date.nowMs() - began;
    u8 *word = t.failed ? "FAIL" : t.skipped ? "SKIP" : "PASS";
    if (t.failed) { self.failed++; }
    else if (t.skipped) { self.skipped++; }
    else { self.passed++; }
    if (t.failed || self.verbose) {
        printf("--- %s: %s (", word, name);
        seconds(took);
        printf(")\n%s", t.out.cstr());
    }
    t.out.free();
    os.flush();
}

/* The summary line; the exit code: 0 all passed, 3 a test failed */
pub i32 M.finish() {
    u64 took = date.nowMs() - self.started;
    i64 total = self.passed + self.failed + self.skipped;
    if (self.failed) {
        printf("FAIL\nFAIL\t%s\t", self.label);
    } else {
        if (self.verbose) { printf("PASS\n"); }
        printf("ok  \t%s\t", self.label);
    }
    seconds(took);
    printf("  (%d passed", self.passed);
    if (self.failed) { printf(", %d failed", self.failed); }
    if (self.skipped) { printf(", %d skipped", self.skipped); }
    if (total == 0) { printf("; no tests to run"); }
    printf(")\n");
    os.flush();
    i32 rc = self.failed ? 3 : 0;
    mem.free(self);
    return rc;
}
