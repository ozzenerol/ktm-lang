import test;

/* Initialised globals were laid out back to back in .data with no
 * alignment directive, so an i64 / f64 / class / pointer global after a
 * `u8 a[3]` landed on an odd address. Each global is now aligned to its
 * type's alignment: a class by its widest field, an array by its element. */

struct Wide {
    i32 a;
    i64 b;
};

struct Narrow {
    i32 a;
    i32 b;
};

struct Half {
    i16 a;
    u8 b;
};

u8 pad0[3] = {1, 2, 3};
i64 g64 = 4;
u8 pad1[3] = {1, 2, 3};
f64 gf64[2] = {1.5, 2.5};
u8 pad2[1] = {1};
Wide gwide = {1, 2};
u8 pad3[1] = {1};
u8 *gptr[1] = {"x"};
u8 pad4[1] = {1};
i32 g32[2] = {1, 2};
u8 pad5[1] = {1};
Narrow gnarrow = {3, 4};
u8 pad6[1] = {1};
i16 g16[2] = {1, 2};
u8 pad7[1] = {1};
Half ghalf = {5, 6};
u8 pad8[1] = {1};
f32 gf32[1] = {1.5};
u8 pad9[1] = {1};
Wide gwides[2] = {{1, 2}, {3, 4}};

/* Zero-initialised (.bss / common) globals. */
u8 bpad0[3];
i64 b64;
u8 bpad1[3];
Wide bwide;
u8 bpad2[1];
i32 b32;
u8 bpad3[1];
i16 b16;

i64 mis(void *p, i64 align) {
    return (i64)p & (align - 1);
}

i64 staticMis() {
    static u8 spad0[3] = {1, 2, 3};
    static i64 s64[1] = {7};
    static u8 spad1[3] = {1, 2, 3};
    static Wide swide = {1, 2};
    static u8 spad2[3];
    static i64 sb64;
    static u8 spad3[1];
    static i32 sb32;
    static u8 spad4[3] = {1, 2, 3};
    static f64 sf64 = 1.5;
    return mis(s64, 8) + mis(&swide, 8) + mis(&sb64, 8) + mis(&sb32, 4) +
                 mis(&sf64, 8) + s64[0] - 7 + swide.b - 2 + (sf64 != 1.5);
}

i32 main() {
    i64 correct = 0, tests = 17;

    printf("Test - global data alignment: ");

    if (mis(&g64, 8) == 0 && g64 == 4) { correct++; }
    if (mis(gf64, 8) == 0 && gf64[1] == 2.5) { correct++; }
    if (mis(&gwide, 8) == 0 && gwide.b == 2) { correct++; }
    if (mis(gptr, 8) == 0 && gptr[0][0] == 'x') { correct++; }
    if (mis(g32, 4) == 0 && g32[1] == 2) { correct++; }
    if (mis(&gnarrow, 4) == 0 && gnarrow.b == 4) { correct++; }
    if (mis(g16, 2) == 0 && g16[1] == 2) { correct++; }
    if (mis(&ghalf, 2) == 0 && ghalf.b == 6) { correct++; }
    if (mis(gf32, 4) == 0 && gf32[0] == 1.5) { correct++; }
    if (mis(gwides, 8) == 0 && gwides[1].b == 4) { correct++; }

    if (mis(&b64, 8) == 0) { correct++; }
    if (mis(&bwide, 8) == 0) { correct++; }
    if (mis(&b32, 4) == 0) { correct++; }
    if (mis(&b16, 2) == 0) { correct++; }

    if (staticMis() == 0) { correct++; }

    /* The bytes around them are intact. */
    if (pad0[2] == 3 && pad1[0] == 1 && pad9[0] == 1) { correct++; }
    if (gwides[0].a == 1 && gnarrow.a == 3 && ghalf.a == 5) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
