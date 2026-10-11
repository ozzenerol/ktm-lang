import test;

/* A class value as an item of a local initialiser list,
 * `L l = {1, p0, 4}` or `P ps[2] = {p0, p0}`, stored the class's
 * address in the first 8 bytes of the field/element instead of copying
 * the class. Globals were right. Class items from a global, a local, a
 * parameter, `*pp`, a field, an array element, a call, an assignment;
 * classes of 1 to 24 bytes, nested, in arrays and unions, mixed with
 * brace-elided scalars. */

struct P { i64 x, y; };              /* 16 */
struct L { i64 a; P p; i64 b; };
struct Q { i64 v; };                 /* 8 */
struct T { i64 a, b, c; };           /* 24 */
struct B { u8 a, b, c; };            /* 3 */
struct W { i32 i; u8 c; };           /* odd fields, padded */
struct Mix { u8 h; B b; Q q; T t; W w; u8 e; };
struct N { i64 h; L l; i64 k; };
struct M { P a[2]; i64 z; };
union U { P p; i64 n; };

P p0 = {2, 3};
B gb = {7, 8, 9};
T gt = {10, 11, 12};
L gl = {1, p0, 4};
P gps[2] = {p0, p0};

P mkP(i64 x, i64 y) {
    P r;
    r.x = x;
    r.y = y;
    return r;
}

T mkT(i64 n) {
    T r;
    r.a = n;
    r.b = n + 1;
    r.c = n + 2;
    return r;
}

bool isL(L *l, i64 a, i64 x, i64 y, i64 b) {
    return l.a == a && l.p.x == x && l.p.y == y && l.b == b;
}

bool isP(P *p, i64 x, i64 y) {
    return p.x == x && p.y == y;
}

bool isB(B *b, i64 a, i64 c2, i64 c) {
    return b.a == a && b.b == c2 && b.c == c;
}

bool isT(T *t, i64 a, i64 b, i64 c) {
    return t.a == a && t.b == b && t.c == c;
}

/* From by-value parameters */
bool fromParams(P q, B b, T t) {
    L l = {5, q, 6};
    P ps[3] = {q, {1, 1}, q};
    B bs[2] = {b, b};
    T ts[2] = {t, t};
    return isL(&l, 5, q.x, q.y, 6) && isP(&ps[0], q.x, q.y) &&
                 isP(&ps[1], 1, 1) && isP(&ps[2], q.x, q.y) &&
                 isB(&bs[0], b.a, b.b, b.c) && isB(&bs[1], b.a, b.b, b.c) &&
                 isT(&ts[0], t.a, t.b, t.c) && isT(&ts[1], t.a, t.b, t.c);
}

i32 main() {
    printf("Test - class item in a local initialiser list: ");
    i64 tests = 0;
    i64 correct = 0;

    /* The bug report */
    L l = {1, p0, 4};
    P ps[2] = {p0, p0};
    tests++;
    if (isL(&l, 1, 2, 3, 4) && isP(&ps[0], 2, 3) && isP(&ps[1], 2, 3)) {
        correct++;
    } else { printf("Failed - global item: %d %d %d %d | %d %d %d %d\n",
                 l.a, l.p.x, l.p.y, l.b, ps[0].x, ps[0].y, ps[1].x, ps[1].y);
    }

    /* From a local, a field, an array element */
    P lp = {20, 21};
    L l2 = {1, lp, 4};
    L l3 = {7, l2.p, 8};
    P ps2[3] = {ps[1], lp, l3.p};
    tests++;
    if (isL(&l2, 1, 20, 21, 4) && isL(&l3, 7, 20, 21, 8) &&
            isP(&ps2[0], 2, 3) && isP(&ps2[1], 20, 21) && isP(&ps2[2], 20, 21)) {
        correct++;
            } else { printf("Failed - local / field / element item\n"); }

    /* Through a pointer */
    P *pp = &lp;
    L *lptr = &l2;
    L l4 = {3, *pp, 9};
    P ps3[2] = {*pp, lptr.p};
    tests++;
    if (isL(&l4, 3, 20, 21, 9) && isP(&ps3[0], 20, 21) &&
            isP(&ps3[1], 20, 21)) {
        correct++;
            } else { printf("Failed - pointer item\n"); }

    /* From a by-value parameter */
    P q = {30, 31};
    B b = {1, 2, 3};
    T t = {4, 5, 6};
    tests++;
    if (fromParams(q, b, t)) { correct++; }
    else { printf("Failed - parameter item\n"); }

    /* From a call: 16 bytes (registers) and 24 bytes (memory) */
    L l5 = {1, mkP(40, 41), 2};
    P ps4[2] = {mkP(42, 43), mkP(44, 45)};
    T ts[2] = {mkT(50), mkT(60)};
    tests++;
    if (isL(&l5, 1, 40, 41, 2) && isP(&ps4[0], 42, 43) &&
            isP(&ps4[1], 44, 45) && isT(&ts[0], 50, 51, 52) &&
            isT(&ts[1], 60, 61, 62)) {
        correct++;
            } else { printf("Failed - call item\n"); }

    /* From an assignment */
    P pa;
    L l6 = {1, pa = p0, 2};
    tests++;
    if (isL(&l6, 1, 2, 3, 2) && isP(&pa, 2, 3)) { correct++; }
    else { printf("Failed - assignment item\n"); }

    /* Classes of 3, 8, 8 (padded), 24 bytes inside a class */
    Q lq = {77};
    W lw = {-5, 200};
    Mix mx = {1, gb, lq, gt, lw, 2};
    tests++;
    if (mx.h == 1 && isB(&mx.b, 7, 8, 9) && mx.q.v == 77 &&
            isT(&mx.t, 10, 11, 12) && mx.w.i == -5 && mx.w.c == 200 &&
            mx.e == 2) {
        correct++;
            } else { printf("Failed - classes of other sizes\n"); }

    /* Arrays of small classes: the neighbours must be kept */
    B bs[4] = {gb, b, gb, b};
    Q qs[3] = {lq, {5}, lq};
    tests++;
    if (isB(&bs[0], 7, 8, 9) && isB(&bs[1], 1, 2, 3) &&
            isB(&bs[2], 7, 8, 9) && isB(&bs[3], 1, 2, 3) &&
            qs[0].v == 77 && qs[1].v == 5 && qs[2].v == 77) {
        correct++;
            } else { printf("Failed - array of small classes\n"); }

    /* Nested: a class item inside a class inside the list */
    N n1 = {1, {2, p0, 3}, 4};
    N n2 = {1, 2, lp, 3, 4};      /* braces of `l` elided */
    L ls[2] = {{1, p0, 2}, {3, lp, 4}};
    L ls2[2] = {1, p0, 2, 3, lp, 4};
    tests++;
    if (n1.h == 1 && isL(&n1.l, 2, 2, 3, 3) && n1.k == 4 &&
            n2.h == 1 && isL(&n2.l, 2, 20, 21, 3) && n2.k == 4 &&
            isL(&ls[0], 1, 2, 3, 2) && isL(&ls[1], 3, 20, 21, 4) &&
            isL(&ls2[0], 1, 2, 3, 2) && isL(&ls2[1], 3, 20, 21, 4)) {
        correct++;
            } else { printf("Failed - nested class item\n"); }

    /* A class item next to brace-elided scalars of the same array */
    M m1 = {p0, 5, 6, 7};
    M m2 = {5, 6, lp, 7};
    tests++;
    if (isP(&m1.a[0], 2, 3) && isP(&m1.a[1], 5, 6) && m1.z == 7 &&
            isP(&m2.a[0], 5, 6) && isP(&m2.a[1], 20, 21) && m2.z == 7) {
        correct++;
            } else { printf("Failed - class item mixed with elided scalars\n"); }

    /* A short list: the rest is still zero */
    L l7 = {1, lp};
    P ps5[3] = {lp};
    tests++;
    if (isL(&l7, 1, 20, 21, 0) && isP(&ps5[0], 20, 21) &&
            isP(&ps5[1], 0, 0) && isP(&ps5[2], 0, 0)) {
        correct++;
            } else { printf("Failed - short list\n"); }

    /* A union set through its class member */
    U u = {lp};
    tests++;
    if (isP(&u.p, 20, 21)) { correct++; }
    else { printf("Failed - union item\n"); }

    /* A copy, not an alias: changing the source later leaves it */
    lp.x = 99;
    tests++;
    if (l2.p.x == 20 && ps2[1].x == 20 && n2.l.p.x == 20) { correct++; }
    else { printf("Failed - copy aliases its source\n"); }

    /* Globals still right */
    tests++;
    if (isL(&gl, 1, 2, 3, 4) && isP(&gps[0], 2, 3) && isP(&gps[1], 2, 3)) {
        correct++;
    } else { printf("Failed - globals\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
