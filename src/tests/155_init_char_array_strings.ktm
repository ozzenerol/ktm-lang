import cstr, fs, libc, mem, proc, test;

/* A string in a char-array slot of an initialiser - a row of
 * `u8 names[2][8]` or a `u8 name[8]` class field - is the string's
 * bytes, zero-filled to the slot. Globals stored a pointer to the
 * literal there, class fields warned "Incompatible value" and stored a
 * pointer (locals too), and local rows copied the escapes undecoded
 * (`"a\nb"` as `a`, `\`, `n`, `b`). */

#define STR_INIT_FILE "__155_init_char_array_strings.ktm"
#define STR_INIT_OUT  "__155_init_char_array_strings.out"

struct C155 {
    i64 id;
    u8 name[8];
    i64 z;
};

struct Outer155 {
    u8 tag[4];
    C155 c;
    u8 *p;
};

u8 g155Names[2][8] = {"ab","cd"};
u8 g155Esc[3][4] = {"a\nb","\x41","\0z"};
u8 g155Top[8] = "a\tb";
C155 g155C = {1,"xy",3};
C155 g155Ce = {4,"\x42\tq",5};
C155 g155Full = {6,"1234567",7};
C155 g155Arr[2] = {{1,"one",2},{3,"two",4}};
Outer155 g155O = {"tg",{8,"in",9},"ptr"};

/* The bytes of `s` at its slot of `n` bytes: `want` then zeros. */
bool slotIs(u8 *s, i64 n, u8 *want, i64 wantLen) {
    i64 i;
    for (i = 0; i < n; i++) {
        if (i < wantLen) {
            if (s[i] != want[i]) { return false; }
        } else if (s[i] != 0) {
            return false;
        }
    }
    return true;
}

bool testGlobalRows(void) {
    bool ok = true;
    if (!slotIs(g155Names[0], 8, "ab", 2)) { ok = false; }
    if (!slotIs(g155Names[1], 8, "cd", 2)) { ok = false; }
    if (!slotIs(g155Esc[0], 4, "a\nb", 3)) { ok = false; }
    if (!slotIs(g155Esc[1], 4, "A", 1)) { ok = false; }
    if (g155Esc[2][0] != 0 || g155Esc[2][1] != 'z' ||
            g155Esc[2][2] != 0 || g155Esc[2][3] != 0) { ok = false; }
    if (!slotIs(g155Top, 8, "a\tb", 3)) { ok = false; }
    return ok;
}

bool testLocalRows(void) {
    u8 names[2][8] = {"ab","cd"};
    u8 esc[3][4] = {"a\nb","\x41","\0z"};
    u8 top[8] = "a\tb";
    bool ok = true;
    if (!slotIs(names[0], 8, "ab", 2)) { ok = false; }
    if (!slotIs(names[1], 8, "cd", 2)) { ok = false; }
    if (!slotIs(esc[0], 4, "a\nb", 3)) { ok = false; }
    if (!slotIs(esc[1], 4, "A", 1)) { ok = false; }
    if (esc[2][0] != 0 || esc[2][1] != 'z' ||
            esc[2][2] != 0 || esc[2][3] != 0) { ok = false; }
    if (!slotIs(top, 8, "a\tb", 3)) { ok = false; }
    return ok;
}

bool testGlobalFields(void) {
    bool ok = true;
    if (g155C.id != 1 || g155C.z != 3) { ok = false; }
    if (!slotIs(g155C.name, 8, "xy", 2)) { ok = false; }
    if (g155Ce.id != 4 || g155Ce.z != 5) { ok = false; }
    if (!slotIs(g155Ce.name, 8, "B\tq", 3)) { ok = false; }
    if (g155Full.id != 6 || g155Full.z != 7) { ok = false; }
    if (!slotIs(g155Full.name, 8, "1234567", 7)) { ok = false; }
    if (g155Arr[0].id != 1 || g155Arr[0].z != 2) { ok = false; }
    if (g155Arr[1].id != 3 || g155Arr[1].z != 4) { ok = false; }
    if (!slotIs(g155Arr[0].name, 8, "one", 3)) { ok = false; }
    if (!slotIs(g155Arr[1].name, 8, "two", 3)) { ok = false; }
    if (!slotIs(g155O.tag, 4, "tg", 2)) { ok = false; }
    if (g155O.c.id != 8 || g155O.c.z != 9) { ok = false; }
    if (!slotIs(g155O.c.name, 8, "in", 2)) { ok = false; }
    /* A pointer field still points at the literal. */
    if (cstr.cmp(g155O.p, "ptr")) { ok = false; }
    return ok;
}

bool testLocalFields(void) {
    C155 c = {1,"xy",3};
    C155 ce = {4,"\x42\tq",5};
    C155 full = {6,"1234567",7};
    C155 arr[2] = {{1,"one",2},{3,"two",4}};
    Outer155 o = {"tg",{8,"in",9},"ptr"};
    bool ok = true;
    if (c.id != 1 || c.z != 3) { ok = false; }
    if (!slotIs(c.name, 8, "xy", 2)) { ok = false; }
    if (ce.id != 4 || ce.z != 5) { ok = false; }
    if (!slotIs(ce.name, 8, "B\tq", 3)) { ok = false; }
    if (full.id != 6 || full.z != 7) { ok = false; }
    if (!slotIs(full.name, 8, "1234567", 7)) { ok = false; }
    if (arr[0].id != 1 || arr[0].z != 2) { ok = false; }
    if (arr[1].id != 3 || arr[1].z != 4) { ok = false; }
    if (!slotIs(arr[0].name, 8, "one", 3)) { ok = false; }
    if (!slotIs(arr[1].name, 8, "two", 3)) { ok = false; }
    if (!slotIs(o.tag, 4, "tg", 2)) { ok = false; }
    if (o.c.id != 8 || o.c.z != 9) { ok = false; }
    if (!slotIs(o.c.name, 8, "in", 2)) { ok = false; }
    if (cstr.cmp(o.p, "ptr")) { ok = false; }
    return ok;
}

void cleanupStrInitTest(void) {
    fs.remove(STR_INIT_FILE);
    fs.remove(STR_INIT_OUT);
    fs.remove("./a.out");
}

/* Compile `source`; return ktm's exit status and its output in `*out`. */
i64 compileSource(u8 *source, u8 **out) {
    i64 res;
    cleanupStrInitTest();
    *out = null;
    if (!fs.writeAll(STR_INIT_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return -1;
    }
    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " STR_INIT_FILE " > " STR_INIT_OUT " 2>&1");
    *out = fs.readAll(STR_INIT_OUT).take();
    cleanupStrInitTest();
    return res;
}

/* A string field initialiser compiles without a diagnostic. */
bool testNoWarning(void) {
    u8 *out;
    i64 res = compileSource(
"struct C { i64 id; u8 name[8]; i64 z; };
C g = {1,\"xy\",3};
void main() { C c = {1,\"xy\",3}; }
", &out);
    bool ok = res == 0 && out && !libc.strstr(out, "warning") && !libc.strstr(out, "error");
    if (!ok) { printf("status %d\n%s\n", res, out); }
    mem.free(out);
    return ok;
}

/* A string that doesn't fit the field with its NUL is an error. */
bool testFieldTooLong(u8 *source, u8 *loc) {
    u8 *out, *first;
    i64 res = compileSource(source, &out);
    u8 *msg = "Invalid array initializer: expected 4 items but got 5";
    bool ok = res == 1 && out;
    if (ok) {
        first = libc.strstr(out, "error: ");
        if (!first || cstr.cmpLen(first + 7, msg, cstr.len(msg))) { ok = false; }
        else if (!libc.strstr(first, loc)) { ok = false; }
        else if (libc.strstr(first + 7, "error: ")) { ok = false; }
    }
    if (!ok) { printf("status %d\n%s\n", res, out); }
    mem.free(out);
    return ok;
}

i32 main() {
    printf("Test - String initialisers for char-array slots: ");
    i64 tests = 7;
    i64 correct = 0;

    if (testGlobalRows()) { correct++; }
    else { printf("Failed - TestGlobalRows()\n"); }

    if (testLocalRows()) { correct++; }
    else { printf("Failed - TestLocalRows()\n"); }

    if (testGlobalFields()) { correct++; }
    else { printf("Failed - TestGlobalFields()\n"); }

    if (testLocalFields()) { correct++; }
    else { printf("Failed - TestLocalFields()\n"); }

    if (testNoWarning()) { correct++; }
    else { printf("Failed - TestNoWarning()\n"); }

    if (testFieldTooLong(
"struct D { u8 n[4]; i64 z; };
D g = {\"abcd\",1};
void main() {}
", "2:8")) { correct++; }
    else { printf("Failed - TestFieldTooLong(global)\n"); }

    if (testFieldTooLong(
"struct D { u8 n[4]; i64 z; };
void main() { D d = {\"abcd\",1}; }
", "2:22")) { correct++; }
    else { printf("Failed - TestFieldTooLong(local)\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
