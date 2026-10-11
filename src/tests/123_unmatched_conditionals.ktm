import test;

import fs, libc, mem, proc, str;

#define COND_FILE "__123_unmatched_conditionals.ktm"
#define COND_OUT  "__123_unmatched_conditionals.out"

void cleanupCondTest(void) {
    fs.remove(COND_FILE);
    fs.remove(COND_OUT);
    fs.remove("./a.out");
}

i64 countErrors(u8 *out) {
    i64 n = 0;
    u8 *p = out;
    while ((p = libc.strstr(p, "error: "))) {
        n++;
        p++;
    }
    return n;
}

/* Compile `source`, which must fail with exactly `count` errors, among
 * them `msg1` at `loc1` (" --> file:line:col") and, when not null,
 * `msg2` at `loc2` and `msg3` at `loc3`. Exactly `count` errors means
 * lexing carried on sensibly after each bad directive. */
bool compileReports(u8 *source, i64 count, u8 *msg1, u8 *loc1,
                                        u8 *msg2, u8 *loc2, u8 *msg3, u8 *loc3) {
    u8 *out, *want;
    u8 *msgs[3], *locs[3];
    i64 res, i;
    bool ok = true;

    msgs[0] = msg1; locs[0] = loc1;
    msgs[1] = msg2; locs[1] = loc2;
    msgs[2] = msg3; locs[2] = loc3;

    cleanupCondTest();

    if (!fs.writeAll(COND_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " COND_FILE " > " COND_OUT " 2>&1");
    if (res == 0) { ok = false; }

    out = fs.readAll(COND_OUT);
    if (!out) {
        cleanupCondTest();
        return false;
    }

    for (i = 0; i < 3; i++) {
        if (!msgs[i]) { break; }
        want = str.format("error: %s\n --> " COND_FILE ":%s\n", msgs[i], locs[i]);
        if (!libc.strstr(out, want)) { ok = false; }
        mem.free(want);
    }
    if (countErrors(out) != count) { ok = false; }
    if (!ok) { printf("%s\n", out); }

    mem.free(out);
    cleanupCondTest();
    return ok;
}

bool testStrayEndif(void) {
    return compileReports(
"i64 a = 1;
  #endif
void main() { printf(\"%d\\n\", a); }
",
        1, "#endif without #if", "2:3", null, null, null, null);
}

bool testStrayElse(void) {
    return compileReports(
"#if 1
#endif
#else
void Main() {}
",
        1, "#else without #if", "3:1", null, null, null, null);
}

/* The stray #elif's condition goes with it, malformed or not */
bool testStrayElif(void) {
    return compileReports(
"#elif (zz+
#elifdef FOO
void Main() {}
",
        2, "#elif without #if", "1:1", "#elifdef without #if", "2:1",
        null, null);
}

/* Nameless: false, and the next line is kept (inside the dead branch,
 * so `b` is undefined), not read as the name */
bool testIfdefNoName(void) {
    return compileReports(
"#ifdef
i64 b = 2;
#endif
void main() { printf(\"%d\\n\", b); }
",
        2, "Syntax is: #ifdef <TK_IDENT>", "1:1",
        "Variable or function `b` has not been defined", "4:21", null, null);
}

bool testIfndefNoName(void) {
    return compileReports(
"i64 c = 3;
#ifndef
i64 c = 4;
#else
#endif
void main() { printf(\"%d\\n\", c); }
",
        1, "Syntax is: #ifndef <TK_IDENT>", "2:1", null, null, null, null);
}

bool testIfdefNotName(void) {
    return compileReports(
"#ifdef 42
i64 d;
#endif
void main() {}
",
        1, "Syntax is: #ifdef <TK_IDENT>", "1:8", null, null, null, null);
}

/* After a taken branch, and after an untaken one */
bool testDoubleElse(void) {
    return compileReports(
"#if 1
i64 e = 5;
#else
i64 f;
#else
i64 g;
#endif
#if 0
#else
i64 h = 8;
 #else
i64 i;
#endif
void main() { printf(\"%d %d\\n\", e, h); }
",
        2, "#else after #else", "5:1", "#else after #else", "11:2", null, null);
}

bool testElifAfterElse(void) {
    return compileReports(
"#if 0
#else
i64 e;
#elif 1
#endif
#ifdef FOO
#else
#elifdef BAR
#endif
void main() {}
",
        2, "#elif after #else", "4:1", "#elifdef after #else", "8:1", null, null);
}

/* Unterminated: both the dead and the taken kind, nested */
bool testUnterminated(void) {
    return compileReports(
"void main() {}
#if 1
  #ifdef FOO
i64 y;
",
        2, "Unterminated #if", "2:1", "Unterminated #if", "3:3", null, null);
}

/* Nested conditionals in a dead branch are skipped whole: their
 * #endifs don't end the outer one and their conditions (even malformed
 * ones) aren't evaluated */
i64 nestedDead() {
    i64 r = 0;
#if 0
  #if 1
    r += 1;
  #endif
    r += 2;
  #if (zz+
  #elif
  #endif
  #ifdef
  #endif
#elif 0
    r += 4;
#else
  #if 0
    r += 8;
  #else
    r += 16;
  #endif
    r += 32;
#endif
    return r;
}

/* A taken branch skips the rest, nested #ifs and all */
i64 nestedTaken() {
    i64 r = 0;
#define NC_TWO 2
#if NC_TWO == 1
    r += 1;
#elif NC_TWO == 2
    r += 2;
  #ifdef NC_TWO
    r += 4;
    #ifndef NC_TWO
    r += 8;
    #elifdef NC_TWO
    r += 16;
    #else
    r += 32;
    #endif
  #endif
#elif 1
    r += 64;
  #if 1
    r += 128;
  #endif
#else
  #if 0
  #else
    r += 256;
  #endif
#endif
#ifjit
    r += 512;
#else
    r += 512;
#endif
    return r;
}

i32 main() {
    printf("Test - unmatched conditional directives: \n");
    i64 tests = 11;
    i64 correct = 0;

    if (testStrayEndif()) { correct++; }
    else { printf("Failed - TestStrayEndif()\n"); }

    if (testStrayElse()) { correct++; }
    else { printf("Failed - TestStrayElse()\n"); }

    if (testStrayElif()) { correct++; }
    else { printf("Failed - TestStrayElif()\n"); }

    if (testIfdefNoName()) { correct++; }
    else { printf("Failed - TestIfdefNoName()\n"); }

    if (testIfndefNoName()) { correct++; }
    else { printf("Failed - TestIfndefNoName()\n"); }

    if (testIfdefNotName()) { correct++; }
    else { printf("Failed - TestIfdefNotName()\n"); }

    if (testDoubleElse()) { correct++; }
    else { printf("Failed - TestDoubleElse()\n"); }

    if (testElifAfterElse()) { correct++; }
    else { printf("Failed - TestElifAfterElse()\n"); }

    if (testUnterminated()) { correct++; }
    else { printf("Failed - TestUnterminated()\n"); }

    if (nestedDead() == 48) { correct++; }
    else { printf("Failed - NestedDead() = %d\n", nestedDead()); }

    if (nestedTaken() == 534) { correct++; }
    else { printf("Failed - NestedTaken() = %d\n", nestedTaken()); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
