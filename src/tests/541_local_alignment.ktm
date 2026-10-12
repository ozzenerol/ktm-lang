import test;

/* Every local sits at its type's natural alignment (v0.7.0): an i64,
 * f64 or pointer after a narrower local was packed at a 4-aligned
 * address, which an atomic access (arm64 LDAXR, x86 LOCK) faults on or
 * splits, and C code may expect aligned. Narrow locals keep theirs;
 * structs get their own; params are 8-aligned. */

struct Mixed { u8 a; f64 d; };
struct Small { u8 a; u16 b; };

bool aligned(void *p, i64 n) { return (i64)p % n == 0; }

i64 params(u8 a, i64 b, u32 c, f64 d) {
    i64 bad = 0;
    if (!aligned(&b, 8)) { bad++; }
    if (!aligned(&d, 8)) { bad++; }
    if (!aligned(&c, 4)) { bad++; }
    return bad + a - a;
}

i64 manyOrders() {
    i64 bad = 0;
    u8 b1 = 1;
    i64 x1 = 2;
    u16 h1 = 3;
    void *p1 = &x1;
    u32 c1 = 4;
    f64 f1 = 1.5;
    u8 b2 = 5;
    Mixed m;
    Small s;
    i32 k1 = 6;
    i64 arr[3];
    u8 bytes[3];
    i64 x2 = 7;
    if (!aligned(&x1, 8) || !aligned(&p1, 8) || !aligned(&f1, 8) || !aligned(&x2, 8)) { bad++; }
    if (!aligned(&h1, 2) || !aligned(&c1, 4) || !aligned(&k1, 4)) { bad++; }
    if (!aligned(&m, 8) || !aligned(&m.d, 8) || !aligned(&s, 2)) { bad++; }
    if (!aligned(&arr, 8) || !aligned(&arr[1], 8)) { bad++; }
    /* the values are all still there */
    if (b1 != 1 || x1 != 2 || h1 != 3 || p1 != &x1 || c1 != 4 || f1 != 1.5 || b2 != 5 ||
        k1 != 6 || x2 != 7) { bad++; }
    bytes[0] = 1;
    arr[2] = 9;
    if (bytes[0] != 1 || arr[2] != 9) { bad++; }
    return bad;
}

i32 main() {
    printf("Test - local alignment: ");
    i64 correct = 0, tests = 0;

    u32 c = 1;
    i64 x = 2;
    void *p = &x;
    tests++; if (aligned(&c, 4) && aligned(&x, 8) && aligned(&p, 8)) { correct++; }
    tests++; if (manyOrders() == 0) { correct++; }
    tests++; if (params(1, 2, 3, 4.0) == 0) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
