import test;

import libc;

/* A call returning a narrow integer (i8/i16/i32, u8/u16/u32) used directly
 * in an expression read the whole 64-bit return register. SysV and AAPCS64
 * leave the bits above the returned width undefined, so
 * `extern "c" i32 strcmp(...); strcmp("a", "b") < 0` was 0 (the -1 came
 * back as 0x00000000ffffffff) while going through an i32 variable worked.
 * The caller now sign- or zero-extends the result, for C functions, ktm
 * functions, function pointers and asm functions. C functions that return
 * a `long` are declared with narrower returns, and a ktm function
 * returning i64 is called through narrower function pointers, so there
 * are set bits above every width. */

extern "c" i32 strcmp(u8 *a, u8 *b);
extern "c" i8 atol(u8 *s);
extern "c" u8 atoll(u8 *s);
extern "c" i16 strtol(u8 *s, u8 **end, i64 base);
extern "c" u16 labs(i64 x);
extern "c" u32 llabs(i64 x);

i64 wide(i64 x) { return x; }

fn(i64 x) i8 gI8;
fn(i64 x) i16 gI16;
fn(i64 x) i32 gI32;
fn(i64 x) u8 gU8;
fn(i64 x) u16 gU16;
fn(i64 x) u32 gU32;

/* ktm functions returning a value wider than their return type */
i32 truncI32(i64 x) { return x; }
u16 truncU16(i64 x) { return x; }
i8 truncI8(i64 x) { return x; }

i64 Id(i64 x) { return x; }

#ifdef IS_X86_64
asm {
_T263_BIG::
        MOV     RAX, 0x12345678FFFFFFFE
        RET
}
pub _extern _T263_BIG i32 asmI32();
pub _extern _T263_BIG u16 asmU16();
#endif

i32 main() {
    printf("Test - narrow return values are extended: ");
    i64 tests = 0;
    i64 correct = 0;

    tests++;
    if (libc.strcmp("a", "b") < 0 && libc.strcmp("b", "a") > 0 && libc.strcmp("a", "a") == 0 &&
            Id(libc.strcmp("a", "b")) < 0 && libc.strcmp("a", "b") + 0 < 0 && -libc.strcmp("a", "b") > 0) {
        correct++;
            } else { printf("Failed - strcmp: %d\n", libc.strcmp("a", "b") < 0); }

    /* C functions returning a long, declared narrower */
    tests++;
    if (libc.atol("511") == -1 && libc.atol("511") < 0 && atoll("511") == 255 &&
            strtol("1ffff", null, 16) == -1 && labs(0x7700000000010002) == 2 &&
            llabs(0x100000007) == 7 && llabs(0x1ffffffff) == 0xffffffff &&
            Id(libc.atol("383")) == 127) {
        correct++;
            } else { printf("Failed - C: %d %d %d %d %d\n", libc.atol("511"), atoll("511"),
                 strtol("1ffff", null, 16), labs(0x7700000000010002), llabs(0x100000007));
    }

    gI8 = (fn(i64) i8)(&wide);
    gI16 = (fn(i64) i16)(&wide);
    gI32 = (fn(i64) i32)(&wide);
    gU8 = (fn(i64) u8)(&wide);
    gU16 = (fn(i64) u16)(&wide);
    gU32 = (fn(i64) u32)(&wide);
    i64 big = 0x7700000000000000;

    /* Signed: the low bits all ones are -1 */
    tests++;
    if (gI8(big | 0xff) == -1 && gI16(big | 0xffff) == -1 &&
            gI32(big | 0xffffffff) == -1 && gI8(big | 0x7f) == 127 &&
            gI32(big | 0x80000000) < 0 && gI16(big | 5) == 5) {
        correct++;
            } else { printf("Failed - signed: %d %d %d\n", gI8(big | 0xff), gI16(big | 0xffff),
                 gI32(big | 0xffffffff));
    }

    /* Unsigned: nothing above the width survives */
    tests++;
    if (gU8(big | 0x1ff) == 0xff && gU16(big | 0x1ffff) == 0xffff &&
            gU32(big | 0x1ffffffff) == 0xffffffff && gU8(big) == 0 &&
            gU32(big | 7) + 1 == 8) {
        correct++;
            } else { printf("Failed - unsigned: %x %x %x\n", gU8(big | 0x1ff),
                 gU16(big | 0x1ffff), gU32(big | 0x1ffffffff));
    }

    /* In comparisons, arithmetic, as arguments and conditions */
    i64 n = 0;
    if (gI32(big | 0xffffffff)) { n++; }
    if (!gU8(big | 0x100)) { n++; }
    tests++;
    if (n == 2 && Id(gI16(big | 0xfffe)) == -2 &&
            gI8(big | 0xfe) * 3 == -6 && (gU16(big | 0x10002) << 1) == 4) {
        correct++;
            } else { printf("Failed - in expressions\n"); }

    /* ktm callees */
    tests++;
    if (truncI32(0x1ffffffff) == -1 && truncI32(0x100000005) == 5 &&
            truncU16(0x3ffff) == 0xffff && truncI8(0x180) == -128 &&
            truncI32(0x1ffffffff) < 0) {
        correct++;
            } else { printf("Failed - ktm: %d %d %d\n", truncI32(0x1ffffffff), truncU16(0x3ffff),
                 truncI8(0x180));
    }

    /* Stored first, as before */
    i32 r = libc.strcmp("a", "b");
    u8 u = gU8(big | 0x1ff);
    tests++;
    if (r < 0 && u == 255) { correct++; }
    else { printf("Failed - via a variable\n"); }

#ifdef IS_X86_64
    tests++;
    if (asmI32() == -2 && asmI32() < 0 && asmU16() == 0xfffe) { correct++; }
    else { printf("Failed - asm: %d %d\n", asmI32(), asmU16()); }
#endif

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
