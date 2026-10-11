import cstr, fs, libc, mem, proc, test;

/* A nested `{ ... }` in a class initialiser initialises the next field,
 * which can be a class or an array: `Bar b = {1, {2}};`. ktm used to
 * recurse with the element type of the class (null) and segfault
 * (exit 139, no diagnostic). */

#define NESTED_INIT_FILE "__129_nested_class_init.ktm"
#define NESTED_INIT_OUT  "__129_nested_class_init.out"

struct Foo129 {
    i64 a;
    i64 b;
};

struct Bar129 {
    i64 x;
    Foo129 f;
    i64 y;
};

struct Arr129 {
    i64 v[3];
    i64 w;
};

struct Deep129 {
    Foo129 fs[2];
    Bar129 bar;
};

struct Top129 {
    Deep129 d;
    i64 z;
};

struct Named129 {
    u8 *name;
    f64 weight;
};

struct Mixed129 {
    i32 a;
    Named129 n;
    u8 c;
    i16 s;
};

Bar129 gBar = {1, {2, 3}, 4};
Arr129 gArr = {{5, 6, 7}, 8};
Top129 gTop = {{{{1, 2}, {3, 4}}, {5, {6, 7}, 8}}, 9};
Bar129 gBars[2] = {{1, {2, 3}, 4}, {5, {6, 7}, 8}};
Foo129 gFoos[2] = {{10, 11}, {12, 13}};

void cleanupNestedInitTest(void) {
    fs.remove(NESTED_INIT_FILE);
    fs.remove(NESTED_INIT_OUT);
    fs.remove("./a.out");
}

/* Compile `source`: ktm must exit with status 1 (not crash) and print
 * `expected`. */
bool compileFailsWith(u8 *source, u8 *expected) {
    u8 *out;
    i64 res;
    bool ok;

    cleanupNestedInitTest();

    if (!fs.writeAll(NESTED_INIT_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " NESTED_INIT_FILE " > " NESTED_INIT_OUT " 2>&1");

    out = fs.readAll(NESTED_INIT_OUT).take();
    if (!out) {
        cleanupNestedInitTest();
        return false;
    }

    /* System gives the exit code: 1 for a compile error. */
    ok = res == 1 && libc.strstr(out, expected) != null;
    if (!ok) { printf("status %d\n%s\n", res, out); }

    mem.free(out);
    cleanupNestedInitTest();
    return ok;
}

bool testIssueRepro(void) {
    Bar129 b = {1, {2}};
    return b.x == 1 && b.f.a == 2;
}

bool testLocalNested(void) {
    Bar129 b = {1, {2, 3}, 4};
    return b.x == 1 && b.f.a == 2 && b.f.b == 3 && b.y == 4;
}

bool testLocalArrayField(void) {
    Arr129 c = {{5, 6, 7}, 8};
    return c.v[0] == 5 && c.v[1] == 6 && c.v[2] == 7 && c.w == 8;
}

bool testLocalDeep(void) {
    Top129 t = {{{{1, 2}, {3, 4}}, {5, {6, 7}, 8}}, 9};
    return t.d.fs[0].a == 1 && t.d.fs[0].b == 2 &&
                 t.d.fs[1].a == 3 && t.d.fs[1].b == 4 &&
                 t.d.bar.x == 5 && t.d.bar.f.a == 6 && t.d.bar.f.b == 7 &&
                 t.d.bar.y == 8 && t.z == 9;
}

bool testLocalArrayOfClasses(void) {
    Bar129 bars[2] = {{1, {2, 3}, 4}, {5, {6, 7}, 8}};
    Foo129 foos[2] = {{10, 11}, {12, 13}};
    return bars[0].x == 1 && bars[0].f.a == 2 && bars[0].f.b == 3 &&
                 bars[0].y == 4 && bars[1].x == 5 && bars[1].f.a == 6 &&
                 bars[1].f.b == 7 && bars[1].y == 8 &&
                 foos[0].a == 10 && foos[0].b == 11 &&
                 foos[1].a == 12 && foos[1].b == 13;
}

/* Fields of different widths, a string and a float in the nested class */
bool testLocalMixed(void) {
    Mixed129 m = {-1, {"name", 2.5}, 200, -3};
    return m.a == -1 && !cstr.cmp(m.n.name, "name") && m.n.weight == 2.5 &&
                 m.c == 200 && m.s == -3;
}

bool testGlobals(void) {
    return gBar.x == 1 && gBar.f.a == 2 && gBar.f.b == 3 && gBar.y == 4 &&
                 gArr.v[0] == 5 && gArr.v[1] == 6 && gArr.v[2] == 7 &&
                 gArr.w == 8 &&
                 gTop.d.fs[0].a == 1 && gTop.d.fs[0].b == 2 &&
                 gTop.d.fs[1].a == 3 && gTop.d.fs[1].b == 4 &&
                 gTop.d.bar.x == 5 && gTop.d.bar.f.a == 6 &&
                 gTop.d.bar.f.b == 7 && gTop.d.bar.y == 8 && gTop.z == 9 &&
                 gBars[1].x == 5 && gBars[1].f.a == 6 && gBars[1].f.b == 7 &&
                 gBars[1].y == 8 && gFoos[1].a == 12 && gFoos[1].b == 13;
}

bool testTooManyInNested(void) {
    return compileFailsWith(
"struct Foo { i64 a; i64 b; };
struct Bar { i64 x; Foo f; };
void main() { Bar b = {1, {2, 3, 4}}; }
", "More initialisers than class fields for class: Foo");
}

bool testTooManyFields(void) {
    return compileFailsWith(
"struct Foo { i64 a; i64 b; };
Foo g = {1, 2, {3}};
void main() {}
", "More initialisers than class fields for class: Foo");
}

bool testScalarField(void) {
    return compileFailsWith(
"struct Foo { i64 a; };
struct Bar { i64 x; Foo f; };
void main() { Bar b = {{1}, {2}}; }
", "Cannot use an initialiser list for field x of type i64");
}

bool testTooManyInArrayField(void) {
    return compileFailsWith(
"struct Arr { i64 v[2]; i64 w; };
Arr g = {{1, 2, 3}, 4};
void main() {}
", "Invalid array initializer: expected 2 items but got 3");
}

bool testScalarElement(void) {
    return compileFailsWith(
"void main() { i64 a[2] = {{{1}}, {2}}; }
", "Cannot use an initialiser list for an element of type i64");
}

i32 main() {
    printf("Test - Nested class initialiser: \n");
    i64 tests = 12;
    i64 correct = 0;

    if (testIssueRepro()) { correct++; }
    else { printf("Failed - TestIssueRepro()\n"); }

    if (testLocalNested()) { correct++; }
    else { printf("Failed - TestLocalNested()\n"); }

    if (testLocalArrayField()) { correct++; }
    else { printf("Failed - TestLocalArrayField()\n"); }

    if (testLocalDeep()) { correct++; }
    else { printf("Failed - TestLocalDeep()\n"); }

    if (testLocalArrayOfClasses()) { correct++; }
    else { printf("Failed - TestLocalArrayOfClasses()\n"); }

    if (testLocalMixed()) { correct++; }
    else { printf("Failed - TestLocalMixed()\n"); }

    if (testGlobals()) { correct++; }
    else { printf("Failed - TestGlobals()\n"); }

    if (testTooManyInNested()) { correct++; }
    else { printf("Failed - TestTooManyInNested()\n"); }

    if (testTooManyFields()) { correct++; }
    else { printf("Failed - TestTooManyFields()\n"); }

    if (testScalarField()) { correct++; }
    else { printf("Failed - TestScalarField()\n"); }

    if (testTooManyInArrayField()) { correct++; }
    else { printf("Failed - TestTooManyInArrayField()\n"); }

    if (testScalarElement()) { correct++; }
    else { printf("Failed - TestScalarElement()\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
