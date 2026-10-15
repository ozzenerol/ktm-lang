import cstr, libc, str, test;

/* A String converts to a u8 * where one is expected (its bytes are
 * NUL-terminated; an empty String gives ""), as s.cstr() does: an
 * argument, a C call, an assignment, a return, a field, typed extras,
 * for a String variable or field (a call's String still is an error:
 * nobody would free it, test 500).
 * An overload taking a u8[] is still the better fit. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

u64 lenOf(u8 *p) { return cstr.len(p); }
u8 *asC(String s) { return s; }
struct Rec { u8 *name; };
u64 total(u8 *...ps) {
    u64 t = 0;
    for (i64 i = 0; i < ps.len; i++) { t += cstr.len(ps[i]); }
    return t;
}
i64 pick(u8 *p) { return 1; }
i64 pick(u8[] p) { return 2; }

String made() { return str.from("abc"); }


i32 main()
{
    printf("Test - a String is a u8 *: ");

    scoped String s = str.from("hello");
    scoped String empty = str.new();

    check(lenOf(s) == 5, "an argument");
    check(lenOf(empty) == 0, "an empty String is \"\"");
    check(cstr.len(s) == 5, "cstr.len(s)");
    check(libc.strlen(s) == 5, "a C call");
    check(libc.strcmp(s, "hello") == 0, "libc.strcmp");

    u8 *p = s;
    check(p == s.ptr, "a declaration: the String's own bytes");
    p = empty;
    check(p != null && p[0] == 0, "an assignment of an empty String");
    check(!libc.strcmp(asC(s), "hello"), "a return");

    Rec r = {s};
    check(!libc.strcmp(r.name, "hello"), "a field");
    check(total(s, empty, "xy") == 7, "typed extras");
    check(pick(s) == 2, "a u8[] overload is the better fit");

    String m = made();
    check(lenOf(m) == 3, "a String variable");
    m.free();
    Rec *rp = &r;
    String *sp = &s;
    check(lenOf(*sp) == 5 && !libc.strcmp(rp.name, "hello"), "through a pointer");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
