import cstr, fs, libc, mem, proc, test;

/* v0.22.0: a struct copy of up to 32 bytes is loads and stores, not a
 * call to memcpy (a u8[] is 16 bytes, a String 24). Every size from 1 to
 * 33 copies right, by assignment, by value into a call and out of one;
 * a larger copy still calls memcpy. */

#define SC_SRC "./ktm-836-copy.ktm"
#define SC_OUT "./ktm-836-copy.s"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

struct B1 { u8 b[1]; };
B1 through1(B1 x) { B1 y = x; return y; }
bool same1() {
    B1 a;
    for i64 i = 0; i < 1; i++ { a.b[i] = i * 7 + 1; }
    B1 b = a;
    B1 c;
    c = through1(b);
    for i64 i = 0; i < 1; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B2 { u8 b[2]; };
B2 through2(B2 x) { B2 y = x; return y; }
bool same2() {
    B2 a;
    for i64 i = 0; i < 2; i++ { a.b[i] = i * 7 + 1; }
    B2 b = a;
    B2 c;
    c = through2(b);
    for i64 i = 0; i < 2; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B3 { u8 b[3]; };
B3 through3(B3 x) { B3 y = x; return y; }
bool same3() {
    B3 a;
    for i64 i = 0; i < 3; i++ { a.b[i] = i * 7 + 1; }
    B3 b = a;
    B3 c;
    c = through3(b);
    for i64 i = 0; i < 3; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B4 { u8 b[4]; };
B4 through4(B4 x) { B4 y = x; return y; }
bool same4() {
    B4 a;
    for i64 i = 0; i < 4; i++ { a.b[i] = i * 7 + 1; }
    B4 b = a;
    B4 c;
    c = through4(b);
    for i64 i = 0; i < 4; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B5 { u8 b[5]; };
B5 through5(B5 x) { B5 y = x; return y; }
bool same5() {
    B5 a;
    for i64 i = 0; i < 5; i++ { a.b[i] = i * 7 + 1; }
    B5 b = a;
    B5 c;
    c = through5(b);
    for i64 i = 0; i < 5; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B6 { u8 b[6]; };
B6 through6(B6 x) { B6 y = x; return y; }
bool same6() {
    B6 a;
    for i64 i = 0; i < 6; i++ { a.b[i] = i * 7 + 1; }
    B6 b = a;
    B6 c;
    c = through6(b);
    for i64 i = 0; i < 6; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B7 { u8 b[7]; };
B7 through7(B7 x) { B7 y = x; return y; }
bool same7() {
    B7 a;
    for i64 i = 0; i < 7; i++ { a.b[i] = i * 7 + 1; }
    B7 b = a;
    B7 c;
    c = through7(b);
    for i64 i = 0; i < 7; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B8 { u8 b[8]; };
B8 through8(B8 x) { B8 y = x; return y; }
bool same8() {
    B8 a;
    for i64 i = 0; i < 8; i++ { a.b[i] = i * 7 + 1; }
    B8 b = a;
    B8 c;
    c = through8(b);
    for i64 i = 0; i < 8; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B9 { u8 b[9]; };
B9 through9(B9 x) { B9 y = x; return y; }
bool same9() {
    B9 a;
    for i64 i = 0; i < 9; i++ { a.b[i] = i * 7 + 1; }
    B9 b = a;
    B9 c;
    c = through9(b);
    for i64 i = 0; i < 9; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B12 { u8 b[12]; };
B12 through12(B12 x) { B12 y = x; return y; }
bool same12() {
    B12 a;
    for i64 i = 0; i < 12; i++ { a.b[i] = i * 7 + 1; }
    B12 b = a;
    B12 c;
    c = through12(b);
    for i64 i = 0; i < 12; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B13 { u8 b[13]; };
B13 through13(B13 x) { B13 y = x; return y; }
bool same13() {
    B13 a;
    for i64 i = 0; i < 13; i++ { a.b[i] = i * 7 + 1; }
    B13 b = a;
    B13 c;
    c = through13(b);
    for i64 i = 0; i < 13; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B15 { u8 b[15]; };
B15 through15(B15 x) { B15 y = x; return y; }
bool same15() {
    B15 a;
    for i64 i = 0; i < 15; i++ { a.b[i] = i * 7 + 1; }
    B15 b = a;
    B15 c;
    c = through15(b);
    for i64 i = 0; i < 15; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B16 { u8 b[16]; };
B16 through16(B16 x) { B16 y = x; return y; }
bool same16() {
    B16 a;
    for i64 i = 0; i < 16; i++ { a.b[i] = i * 7 + 1; }
    B16 b = a;
    B16 c;
    c = through16(b);
    for i64 i = 0; i < 16; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B17 { u8 b[17]; };
B17 through17(B17 x) { B17 y = x; return y; }
bool same17() {
    B17 a;
    for i64 i = 0; i < 17; i++ { a.b[i] = i * 7 + 1; }
    B17 b = a;
    B17 c;
    c = through17(b);
    for i64 i = 0; i < 17; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B23 { u8 b[23]; };
B23 through23(B23 x) { B23 y = x; return y; }
bool same23() {
    B23 a;
    for i64 i = 0; i < 23; i++ { a.b[i] = i * 7 + 1; }
    B23 b = a;
    B23 c;
    c = through23(b);
    for i64 i = 0; i < 23; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B24 { u8 b[24]; };
B24 through24(B24 x) { B24 y = x; return y; }
bool same24() {
    B24 a;
    for i64 i = 0; i < 24; i++ { a.b[i] = i * 7 + 1; }
    B24 b = a;
    B24 c;
    c = through24(b);
    for i64 i = 0; i < 24; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B25 { u8 b[25]; };
B25 through25(B25 x) { B25 y = x; return y; }
bool same25() {
    B25 a;
    for i64 i = 0; i < 25; i++ { a.b[i] = i * 7 + 1; }
    B25 b = a;
    B25 c;
    c = through25(b);
    for i64 i = 0; i < 25; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B31 { u8 b[31]; };
B31 through31(B31 x) { B31 y = x; return y; }
bool same31() {
    B31 a;
    for i64 i = 0; i < 31; i++ { a.b[i] = i * 7 + 1; }
    B31 b = a;
    B31 c;
    c = through31(b);
    for i64 i = 0; i < 31; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B32 { u8 b[32]; };
B32 through32(B32 x) { B32 y = x; return y; }
bool same32() {
    B32 a;
    for i64 i = 0; i < 32; i++ { a.b[i] = i * 7 + 1; }
    B32 b = a;
    B32 c;
    c = through32(b);
    for i64 i = 0; i < 32; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B33 { u8 b[33]; };
B33 through33(B33 x) { B33 y = x; return y; }
bool same33() {
    B33 a;
    for i64 i = 0; i < 33; i++ { a.b[i] = i * 7 + 1; }
    B33 b = a;
    B33 c;
    c = through33(b);
    for i64 i = 0; i < 33; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}
struct B40 { u8 b[40]; };
B40 through40(B40 x) { B40 y = x; return y; }
bool same40() {
    B40 a;
    for i64 i = 0; i < 40; i++ { a.b[i] = i * 7 + 1; }
    B40 b = a;
    B40 c;
    c = through40(b);
    for i64 i = 0; i < 40; i++ {
        if c.b[i] != (u8)(i * 7 + 1) { return false; }
    }
    return true;
}

/* the bytes either side of a copy into an array element stay as they were */
B5 gArr[3];

i64 memcpyCalls(u8 *source) {
    proc.shell("rm -f " SC_SRC " " SC_OUT);
    fs.writeAll(SC_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               "-S " SC_SRC " -o " SC_OUT " > /dev/null 2>&1");
    u8 *s = fs.readAll(SC_OUT).take();
    proc.shell("rm -f " SC_SRC " " SC_OUT);
    i64 n = 0;
    u8 *p = s;
    while p && (p = libc.strstr(p, "memcpy")) {
        n++;
        p++;
    }
    mem.free(s);
    return s ? n : -1;
}

i32 main() {
    printf("Test - small copies inline: ");
    check(same1(), "a 1-byte struct copies right");
    check(same2(), "a 2-byte struct copies right");
    check(same3(), "a 3-byte struct copies right");
    check(same4(), "a 4-byte struct copies right");
    check(same5(), "a 5-byte struct copies right");
    check(same6(), "a 6-byte struct copies right");
    check(same7(), "a 7-byte struct copies right");
    check(same8(), "a 8-byte struct copies right");
    check(same9(), "a 9-byte struct copies right");
    check(same12(), "a 12-byte struct copies right");
    check(same13(), "a 13-byte struct copies right");
    check(same15(), "a 15-byte struct copies right");
    check(same16(), "a 16-byte struct copies right");
    check(same17(), "a 17-byte struct copies right");
    check(same23(), "a 23-byte struct copies right");
    check(same24(), "a 24-byte struct copies right");
    check(same25(), "a 25-byte struct copies right");
    check(same31(), "a 31-byte struct copies right");
    check(same32(), "a 32-byte struct copies right");
    check(same33(), "a 33-byte struct copies right");
    check(same40(), "a 40-byte struct copies right");

    for i64 i = 0; i < 3; i++ {
        for i64 j = 0; j < 5; j++ { gArr[i].b[j] = 0xEE; }
    }
    B5 five;
    for i64 j = 0; j < 5; j++ { five.b[j] = j; }
    gArr[1] = five;
    check(gArr[0].b[4] == 0xEE && gArr[2].b[0] == 0xEE && gArr[1].b[4] == 4,
          "a 5-byte copy writes 5 bytes, not 8");

    u8[] s = "abc";
    s = s;
    check(s.len == 3 && s.ptr[2] == 'c', "a copy onto itself");

    check(memcpyCalls("struct P { i64 a; i64 b; i64 c; };\nP f(P *p) { P q = *p; return q; }\n"
                      "i32 main() { return 0; }\n") == 0, "a 24-byte copy calls no memcpy");
    check(memcpyCalls("struct P { i64 a[5]; };\nvoid f(P *p, P *q) { *q = *p; }\n"
                      "i32 main() { return 0; }\n") > 0, "a 40-byte copy still calls memcpy");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
