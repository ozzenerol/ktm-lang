import cstr, fs, libc, proc, test;

/* ktm syntax v0.2: void i8 u8 i16 u16 i32 u32 i64 u64 f32 f64 bool are
 * the built-in types. They are type names, not keywords: a struct field
 * can still be called i64. typeof and diagnostics print them. The old
 * spellings (U0 I8 ... F64 Bool, class, public) are errors that name the
 * new one. */

struct Mix {
    i8 a;
    u8 b;
    i16 c;
    u16 d;
    i32 e;
    u32 f;
    i64 g;
    u64 h;
    f32 x;
    f64 y;
    bool z;
    i64 i64_;       /* a field may use a type's name */
    f64 f64_;
};

i64 gCount = 3;
u8 *gName = "ktm";

i64 add(i64 a, i64 b = 10) { return a + b; }
f64 half(f64 v) { return v / 2; }
bool isNeg(i32 v) { return v < 0; }
void bump(i64 *p) { *p += 1; }
u8 *nameFn() { return gName; }

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - lowercase built-in types: ");

    tests++;
    if (sizeof(i8) == 1 && sizeof(u8) == 1 && sizeof(i16) == 2 && sizeof(u16) == 2 &&
            sizeof(i32) == 4 && sizeof(u32) == 4 && sizeof(i64) == 8 && sizeof(u64) == 8 &&
            sizeof(f32) == 4 && sizeof(f64) == 8 && sizeof(bool) == 1) { correct++; }
    else { printf("\n  sizes\n"); }

    tests++;   /* signedness and wrap-around, as the old names */
    i8 sc = 200;
    u8 uc = 200;
    i16 ss = 40000;
    u16 us = 70000;
    i32 si = 0xFFFFFFFF;
    u32 ui = 0xFFFFFFFF;
    u64 big = -1;
    if (sc == -56 && uc == 200 && ss == -25536 && us == 4464 && si == -1 &&
            ui == 0xFFFFFFFF && big > 0) { correct++; }
    else { printf("\n  signedness\n"); }

    tests++;   /* bool converts like C's _Bool */
    bool t = 0x800, f = 0;
    if (t == true && f == false && isNeg(-4) && !isNeg(4)) { correct++; }
    else { printf("\n  bool\n"); }

    tests++;   /* functions, defaults, pointers, globals */
    i64 n = 1;
    bump(&n);
    if (add(1) == 11 && add(1, 2) == 3 && half(5) == 2.5 && n == 2 &&
            gCount == 3 && !cstr.cmp(nameFn(), "ktm")) { correct++; }
    else { printf("\n  functions\n"); }

    tests++;   /* struct fields, including fields named like types */
    Mix m;
    m.i64_ = 42;
    m.f64_ = 1.25;
    m.z = 7;
    if (m.i64_ == 42 && m.f64_ == 1.25 && m.z == 1 && sizeof(Mix) >= 50) { correct++; }
    else { printf("\n  struct fields\n"); }

    tests++;   /* arrays and casts */
    i32 arr[4] = {1, 2, 3, 4};
    f64 avg = (f64)(arr[0] + arr[1] + arr[2] + arr[3]) / 4;
    if (avg == 2.5 && sizeof(arr) == 16) { correct++; }
    else { printf("\n  arrays and casts\n"); }

    tests++;   /* old and new names are the same types */
    i64 oldI = 5;
    i64 *pi = &oldI;
    u8 *oldS = "x";
    u8 *ps = oldS;
    if (*pi == 5 && *ps == 'x' && sizeof(i64) == sizeof(i64)) { correct++; }
    else { printf("\n  old names\n"); }

    tests++;   /* typeof prints the new names */
    if (!cstr.cmp(typeof(oldI), "i64") && !cstr.cmp(typeof(half(1.0)), "f64") &&
            !cstr.cmp(typeof(t), "bool") && !cstr.cmp(typeof(ps), "u8 *") &&
            !cstr.cmp(typeof(arr), "i32[4]") && !cstr.cmp(typeof(sc), "i8")) { correct++; }
    else { printf("\n  typeof: %s %s %s %s %s\n", typeof(oldI), typeof(half(1.0)), typeof(t), typeof(ps), typeof(arr)); }

    tests++;   /* and so do diagnostics */
    u8 *src = "i64 g;\nvoid A() { return g; }\nvoid main() { A(); }\n";
    fs.writeAll("__400_warn.ktm", cstr.view(src));
    u8 *out = proc.shellOutput("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit __400_warn.ktm 2>&1");
    if (out && libc.strstr(out, "of type 'i64' expected 'void'")) { correct++; }
    else { printf("\n  diagnostic names: [%s]\n", out); }

    tests++;   /* the old spellings, one error each */
    src = "class P { I64 x; };\npublic U0 F(Bool b) { }\nvoid main() { }\n";
    fs.writeAll("__400_warn.ktm", cstr.view(src));
    out = proc.shellOutput("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -S -o /dev/null __400_warn.ktm 2>&1");
    if (out && libc.strstr(out, "`class` is the old spelling: write `struct`") &&
        libc.strstr(out, "`I64` is the old spelling: write `i64`") &&
        libc.strstr(out, "`public` is the old spelling: write `pub`") &&
        libc.strstr(out, "`U0` is the old spelling: write `void`") &&
        libc.strstr(out, "`Bool` is the old spelling: write `bool`") &&
        !libc.strstr(out, "has not been")) { correct++; }
    else { printf("\n  old spellings: [%s]\n", out); }
    fs.remove("__400_warn.ktm");

    proc.shell("rm -rf __400_*");   /* the files this test wrote */
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
