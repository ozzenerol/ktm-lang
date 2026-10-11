import cstr, test;

/* A global array or class initialiser is written out as static data, which
 * only held literals and strings: any other item - `&gx`, `&Foo`, `gx`,
 * `&arr[1]` - was silently left as 0. Such items are now stored at startup,
 * like a scalar global's `i64 g = gx;`. */

struct Pt {
    i64 x;
    i64 y;
};

struct Fn {
    fn(i64 v) i64 f;
    i64 k;
};

struct Mixed {
    u8 tag;
    i32 n;
    f64 d;
};

i64 dbl(i64 v) { return v * 2; }
i64 inc(i64 v) { return v + 1; }

i64 gx = 5;
i64 gy = 9;
f64 gf = 2.5;
i64 garr[3] = {10, 20, 30};
Pt gp = {7, 8};

i64 *arrp[4] = {&gx, &garr[1], &gp.y, null};
i64 nonc[3] = {gx, 2, gx + gy};
i64 mix[4] = {1, gx};
u8 bytes[4] = {1, gx, 3};
f64 floats[2] = {gf, 1.5};
void *fns[2] = {&dbl, &inc};
u8 *strs[2] = {"ab", "cd"};
Pt gq = {gy, 3};
Pt pts[2] = {{1, gx}, {gy, 4}};
Fn gfn = {&dbl, 4};
Fn ftab[2] = {{&dbl, 1}, {&inc, 2}};
Mixed gm = {7, gx, gf};
i64 g2 = gx * 2;
i64 dep[2] = {g2, g2 + 1};

i64 staticGet(i64 i) {
    static i64 *tab[2] = {&gx, &gy};
    i64 *raw = (i64 *)tab;
    return raw[i];
}

i32 main() {
    i64 correct = 0, tests = 30;

    printf("Test - non-constant items in a global initialiser: ");

    /* Read the pointer slots as I64s. */
    i64 *raw = (i64 *)arrp;
    if (raw[0] == (i64)(&gx)) { correct++; }
    if (raw[1] == (i64)(&garr[1])) { correct++; }
    if (raw[2] == (i64)(&gp.y)) { correct++; }
    if (raw[3] == 0) { correct++; }

    if (nonc[0] == 5) { correct++; }
    if (nonc[1] == 2) { correct++; }
    if (nonc[2] == 14) { correct++; }

    if (mix[0] == 1 && mix[1] == 5) { correct++; }
    if (mix[2] == 0 && mix[3] == 0) { correct++; }

    if (bytes[0] == 1 && bytes[1] == 5) { correct++; }
    if (bytes[2] == 3 && bytes[3] == 0) { correct++; }

    if (floats[0] == 2.5) { correct++; }
    if (floats[1] == 1.5) { correct++; }

    if (fns[0] == (void *)(&dbl)) { correct++; }
    if (ftab[0].f(4) == 8 && ftab[1].f(4) == 5) { correct++; }

    if (!cstr.cmp(strs[0], "ab") && !cstr.cmp(strs[1], "cd")) { correct++; }

    if (gq.x == 9 && gq.y == 3) { correct++; }
    if (pts[0].x == 1 && pts[0].y == 5) { correct++; }
    if (pts[1].x == 9 && pts[1].y == 4) { correct++; }

    if (gfn.f(21) == 42) { correct++; }
    if (gfn.k == 4) { correct++; }

    if (gm.tag == 7) { correct++; }
    if (gm.n == 5) { correct++; }
    if (gm.d == 2.5) { correct++; }

    /* Run in program order, after the globals they read. */
    if (g2 == 10) { correct++; }
    if (dep[0] == 10 && dep[1] == 11) { correct++; }

    if (staticGet(0) == (i64)(&gx)) { correct++; }
    if (staticGet(1) == (i64)(&gy)) { correct++; }

    /* The stored pointers are live. */
    gx = 6;
    i64 *p = (i64 *)raw[0];
    if (*p == 6) { correct++; }
    p = (i64 *)raw[1];
    if (*p == 20) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
