#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* An i8, u8 or bool value couldn't index an array or be added to a
 * pointer ("`u8[8] + u8` is invalid": they are char-kind types, and only
 * int-kind ones were accepted), and for `buf[i]` the error pointed at an
 * earlier line, at whatever `+` came before. Every integer type is an
 * index and an offset now, zero- or sign-extended by its own type, and a
 * bad subscript is reported at the subscript. */

#define BAD_FILE "__387_bad.ktm"
#define BAD_OUT  "__387_bad.out"

struct CIdx {
    u8 u8_;
    i8 i8_;
    bool b;
};

/* arr[k] holds k - 1024, so base = arr + 1024 has base[k] == k */
i32 arr[2048];
i32 *base;

i64 byU8(u8 i)  { return base[i]; }
i64 byI8(i8 i)  { return base[i]; }
i64 byBool(bool i) { return *(base + i); }

i64 check(i64 got, i64 want, u8 *what) {
    if (got == want) { return 1; }
    printf("\n  %s: got %d, want %d", what, got, want);
    return 0;
}

/* Compiles `src`; true when it fails with the subscript error on `line` */
bool badSubscriptAt(u8 *src, i64 line) {
    u8 *out, want[64];
    i64 res;
    bool ok;
    fs.remove(BAD_FILE);
    fs.writeAll(BAD_FILE, src, str.len(src));
    res = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " BAD_FILE " > " BAD_OUT " 2>&1");
    out = fs.readAll(BAD_OUT);
    str.formatTo(want, BAD_FILE ":%d:", line);
    ok = res != 0 && out && libc.strstr(out, want) &&
             libc.strstr(out, "Subscript expressions must evaluate to an integer type");
    if (!ok && out) { printf("\n%s", out); }
    mem.free(out);
    fs.remove(BAD_FILE);
    fs.remove(BAD_OUT);
    return ok;
}

i32 main() {
    printf("Test - i8/u8/bool index and offset: ");
    i64 correct = 0, tests = 0, k;
    for (k = 0; k < 2048; k++) { arr[k] = k - 1024; }
    base = arr + 1024;

    u8 vU8 = 255;  i8 vI8 = -1;  bool b = true;
    u16 vU16 = 65535 % 1000; i16 vI16 = -2;
    u32 vU32 = 1000;  i32 vI32 = -3;
    u64 vU64 = 7;  i64 vI64 = -1000;

    /* every integer type as an index */
    tests++; correct += check(base[vU8], 255, "base[u8 255]");
    tests++; correct += check(base[vI8], -1, "base[i8 -1]");
    tests++; correct += check(base[b], 1, "base[bool true]");
    tests++; correct += check(base[vU16], 535, "base[u16]");
    tests++; correct += check(base[vI16], -2, "base[i16]");
    tests++; correct += check(base[vU32], 1000, "base[u32]");
    tests++; correct += check(base[vI32], -3, "base[i32]");
    tests++; correct += check(base[vU64], 7, "base[u64]");
    tests++; correct += check(base[vI64], -1000, "base[i64]");

    /* and as a pointer offset, either side of `+`, and with `-` */
    tests++; correct += check(*(base + vU8), 255, "base + u8");
    tests++; correct += check(*(vU8 + base), 255, "u8 + base");
    tests++; correct += check(*(base + vI8), -1, "base + i8");
    tests++; correct += check(*(base - vI8), 1, "base - i8");
    tests++; correct += check(*(base - vU8), -255, "base - u8");
    tests++; correct += check(*(base + b), 1, "base + bool");

    /* the extremes, and values whose top bit is set */
    vI8 = -128; vU8 = 128;
    tests++; correct += check(base[vI8], -128, "base[i8 -128]");
    tests++; correct += check(base[vU8], 128, "base[u8 128]");
    vI8 = 200; /* wraps to -56 */
    tests++; correct += check(base[vI8], -56, "base[i8 200]");
    tests++; correct += check(base[(u8)255], 255, "base[255(u8)]");
    tests++; correct += check(base[-(i8)1], -1, "base[-1(i8)]");

    /* from parameters, class fields and array elements */
    tests++; correct += check(byU8(250), 250, "u8 parameter");
    tests++; correct += check(byI8(-7), -7, "i8 parameter");
    tests++; correct += check(byBool(false), 0, "bool parameter");
    CIdx c;
    c.u8_ = 240; c.i8_ = -100; c.b = true;
    tests++; correct += check(base[c.u8_], 240, "u8 field");
    tests++; correct += check(base[c.i8_], -100, "i8 field");
    tests++; correct += check(base[c.b], 1, "bool field");
    u8 bytes[2]; i8 sbytes[2];
    bytes[1] = 230; sbytes[1] = -9;
    tests++; correct += check(base[bytes[1]], 230, "u8 element");
    tests++; correct += check(base[sbytes[1]], -9, "i8 element");

    /* a u8 buffer indexed by a u8, read and written; += and -= */
    u8 buf[256], *p = buf;
    for (k = 0; k < 256; k++) { buf[k] = k; }
    vU8 = 255; vI8 = -1;
    buf[vU8] = 'z';
    tests++; correct += check(buf[255], 'z', "buf[u8] = 'z'");
    p += vU8;
    tests++; correct += check(p - buf, 255, "p += u8");
    p += vI8;
    tests++; correct += check(p - buf, 254, "p += i8");
    p -= b;
    tests++; correct += check(p - buf, 253, "p -= bool");
    tests++; correct += check(p[vI8], 252, "p[i8 -1]");

    /* a non-integer subscript is reported at the subscript, not the line
     * before (where the previous `+` is) */
    tests++;
    if (badSubscriptAt(
"struct C { i64 a; };
void main()
{
  i64 x = 1 + 2;
  C c;
  u8 buf[4];
  buf[c] = 1;
}
", 7)) { correct++; }
    else { printf("class subscript "); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
