#include "testhelper.ktm"

import fs, json;

/* A value converted to bool kept only its low byte: 0x100, 0x800 and 0.5
 * became false, -1 stayed -1 and a pointer gave its low byte. As in C it
 * is now 0 or 1 (value != 0) for arguments, assignments, initialisers,
 * return values, class fields, casts, default arguments and ++/--. bool
 * stays one byte. Library code that kept other values in a bool (Rm's and
 * RmDir's flags, JsonParseBool's -1 for an error) uses an i64 now. */

struct C {
    u8 a;
    bool b;
    u8 c;
};

bool gInt = 0x100;
bool gFlt = 0.5;

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* The byte as stored: exactly 0 or 1 */
bool is(bool b, i64 want) {
    return ((u8 *)(&b))[0] == want;
}

i64 Arg(bool b) { return ((u8 *)(&b))[0]; }
i64 defArg(bool b = 0x800) { return ((u8 *)(&b))[0]; }
bool retInt(i64 x) { return x; }
bool retU64(u64 x) { return x; }
bool retFlt(f64 x) { return x; }
bool retPtr(u8 *x) { return x; }

void args(u8 *p) {
    u64 h = 0x100, big = 0x800;
    i64 m = -1;
    f64 f = 0.5;
    f32 vF32 = 0.25;
    fn(bool) i64 fp = &Arg;
    check(Arg(h) == 1, "arg 0x100");
    check(Arg(big) == 1, "arg 0x800");
    check(Arg(m) == 1, "arg -1");
    check(Arg(f) == 1, "arg 0.5");
    check(Arg(vF32) == 1, "arg f32 0.25");
    check(Arg(p) == 1, "arg pointer");
    check(Arg(0) == 0 && Arg(0.0) == 0 && Arg(null) == 0, "arg 0");
    check(Arg(0x100) == 1 && Arg(0.5) == 1, "arg constants");
    check(fp(big) == 1, "arg through a function pointer");
    check(defArg() == 1, "default 0x800, called with ()");
    check(defArg() == 1, "default 0x800, called without ()");
    check(defArg(0x200) == 1, "0x200 for a defaulted bool");
}

void assign(u8 *p) {
    u64 h = 0x100, big = 0x800;
    i64 m = -1;
    f64 f = 0.5, nz = -0.0, nan = 0.0 / 0.0;
    bool b, z = 0, *bp = &z;

    b = h;   check(is(b, 1), "assign 0x100");
    b = big; check(is(b, 1), "assign 0x800");
    b = m;   check(is(b, 1), "assign -1");
    b = f;   check(is(b, 1), "assign 0.5");
    b = p;   check(is(b, 1), "assign pointer");
    b = 0;   check(is(b, 0), "assign 0");
    b = nz;  check(is(b, 0), "assign -0.0");
    b = nan; check(is(b, 1), "assign NaN");
    check((b = 0x800) == 1, "the assignment's value is 1");
    *bp = 0x400; check(is(z, 1), "store through a bool *");

    bool i1 = h, i2 = f, i3 = p, i4 = -1;
    check(is(i1, 1) && is(i2, 1) && is(i3, 1) && is(i4, 1), "initialisers");
    check(is(gInt, 1) && is(gFlt, 1), "global initialisers");

    b = 0; b |= 0x100; check(is(b, 1), "|= 0x100");
    b = 1; b += 1;     check(is(b, 1), "+= 1");
    b = 1; b++;        check(is(b, 1), "++ on true");
    b = 0; ++b;        check(is(b, 1), "++ on false");
    b = 1; b--;        check(is(b, 0), "-- on true");
    b = 0; b--;        check(is(b, 1), "-- on false");
}

void fields(u8 *p) {
    u64 big = 0x800;
    C c;
    C d = {7, 0x200, 9};
    bool arr[4] = {0x100, 0, -1, 0.5};

    check(sizeof(bool) == 1 && sizeof(C) == 3, "bool is one byte");
    check((u8 *)(&c.b) - (u8 *)(&c) == 1 && (u8 *)(&c.c) - (u8 *)(&c) == 2,
            "field offsets");
    c.a = 7; c.c = 9;
    c.b = big; check(is(c.b, 1) && c.a == 7 && c.c == 9, "field 0x800");
    c.b = p;   check(is(c.b, 1) && c.a == 7 && c.c == 9, "field pointer");
    check(is(d.b, 1) && d.a == 7 && d.c == 9, "class initialiser");
    check(is(arr[0], 1) && is(arr[1], 0) && is(arr[2], 1) && is(arr[3], 1),
            "array initialiser");
}

void returns(u8 *p) {
    check(is(retInt(0x100), 1), "return 0x100");
    check(is(retU64(0x800), 1), "return 0x800");
    check(is(retInt(-1), 1), "return -1");
    check(is(retFlt(0.5), 1), "return 0.5");
    check(is(retPtr(p), 1), "return a pointer");
    check(is(retInt(0), 0) && is(retPtr(null), 0), "return 0");
}

void casts(u8 *p) {
    u64 big = 0x800;
    i64 m = -1;
    f64 f = 0.5;
    bool b;
    b = (bool)big; check(is(b, 1), "cast 0x800");
    b = (bool)m;   check(is(b, 1), "cast -1");
    b = (bool)f;   check(is(b, 1), "cast 0.5");
    b = (bool)p;   check(is(b, 1), "cast pointer");
    /* Constant casts, folded by the compiler */
    b = (bool)0x100; check(is(b, 1), "constant cast 0x100");
    b = (bool)0.5;   check(is(b, 1), "constant cast 0.5");
    check((bool)0x100 == 1 && (bool)(-1) == 1 && (bool)0 == 0,
            "constant casts in expressions");
}

extern "c" i32 access(u8 *path, i32 mode);
bool exists(u8 *path) { return access(path, 0) == 0; }

i64 narrow8(i8 x) { return x; }
i64 narrowU8(u8 x) { return x; }

void library() {
    /* The other byte types still keep the low byte */
    i8 c = 0x181;
    check(narrow8(0x181) == -127 && narrowU8(0x1FF) == 255 && c == -127,
            "i8 and u8 still truncate");

    /* RM_VERBOSE alone must not act like RM_RECURSE (2 as a bool is 1) */
    fs.makeDir("./ktm-388/sub", fs.MAKE_DIR_DEFAULT, true);
    fs.writeAll("./ktm-388/sub/f", "x", 1);
    check(fs.removeDir("./ktm-388", RM_VERBOSE) != 0 && exists("./ktm-388/sub/f"),
            "RmDir with RM_VERBOSE doesn't recurse");
    check(fs.removeDir("./ktm-388", fs.REMOVE_RECURSE) == 0 && !exists("./ktm-388"),
            "RmDir with RM_RECURSE");

    /* JsonParseBool reports a bad literal (it returned -1 through a bool) */
    json.Value *j = json.parse("[trux]");
    check(!json.ok(j) && j.state.error == json.INVALID_LITERAL,
            "[trux] is JSON_INVALID_LITERAL");
    json.release(j);
}

i32 main() {
    u8 *p = "x";
    printf("Test - conversions to bool: ");
    args(p);
    assign(p);
    fields(p);
    returns(p);
    casts(p);
    library();
    PrintResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
