#include "testhelper.ktm"

/* `i64 *z = v; v = &x;` made z follow v: the IR store forwarding
 * recorded "z holds v" and kept rewriting reads of z into reads of v
 * after v was given a new address (`lea v, x` is not a store, so the
 * entry was never dropped). `*z` read x instead of y, and with
 * `v = null` it crashed. A copy must keep the value it was given. */

struct Node {
    i64 val;
    Node *next;
};

i64 gA = 11, gB = 22;
i64 *gP = &gA;

i64 paramCopy(i64 *v, i64 *other) {
    i64 *z = v;
    v = other;
    return *z * 100 + *v;
}

i64 paramCopyNull(i64 *v) {
    i64 *z = v;
    v = null;
    if (v == null) { return *z; }
    return -1;
}

i32 main() {
    i64 correct = 0, tests = 16, i;
    i64 x = 3, y = 7;

    printf("Test - pointer copy keeps its value after the source changes: ");

    /* Declaration copy, then the source gets a new address */
    i64 *v = &y;
    i64 *z = v;
    v = &x;
    if (*z == 7 && z == &y && *v == 3) { correct++; }

    /* Plain assignment copy */
    i64 *v2, *z2;
    v2 = &y;
    z2 = v2;
    v2 = &x;
    if (*z2 == 7 && z2 == &y) { correct++; }

    /* Source set to null: the copy must not become null */
    i64 *v3 = &y;
    i64 *z3 = v3;
    v3 = null;
    if (v3 == null && *z3 == 7) { correct++; }

    /* void * */
    void *u = &y;
    i64 *zu = u;
    u = &x;
    void *zu2 = u;
    u = null;
    if (*zu == 7 && zu2 == &x && u == null) { correct++; }

    /* Parameters */
    if (paramCopy(&y, &x) == 703) { correct++; }
    if (paramCopyNull(&y) == 7) { correct++; }

    /* Class pointers */
    Node a, b;
    a.val = 1; a.next = &b;
    b.val = 2; b.next = null;
    Node *n = &a;
    Node *keep = n;
    n = n.next;
    if (keep.val == 1 && n.val == 2 && keep == &a) { correct++; }

    /* Globals */
    i64 *gz = gP;
    gP = &gB;
    if (*gz == 11 && *gP == 22) { correct++; }
    i64 *lp = &gA;
    i64 *lz = lp;
    lp = &gB;
    if (*lz == 11 && *lp == 22) { correct++; }

    /* Pointer to pointer */
    i64 *pa = &x, *pb = &y;
    i64 **pp = &pa;
    i64 **pz = pp;
    pp = &pb;
    if (pz == &pa && **pz == 3 && **pp == 7) { correct++; }

    /* Walking a list: prev keeps the node it was given */
    Node c;
    c.val = 3; c.next = null;
    b.next = &c;
    Node *cur = &a, *prev = null;
    i64 sum = 0;
    while (cur) {
        prev = cur;
        cur = cur.next;
        if (cur) { sum += prev.val * 10 + cur.val; }
    }
    if (prev == &c && sum == 12 + 23) { correct++; }

    /* Copy then reassign in a loop */
    i64 arr[4] = {10, 20, 30, 40};
    i64 *p = arr, *q;
    i64 ok = 1;
    for (i = 0; i < 3; i++) {
        q = p;
        p = p + 1;
        if (*q != arr[i] || *p != arr[i + 1]) { ok = 0; }
    }
    if (ok) { correct++; }

    /* Copy then reassign in branches */
    i64 *bv = &x;
    i64 *bz = bv;
    if (x == 3) { bv = &y; } else { bv = null; }
    if (*bz == 3 && *bv == 7) { correct++; }
    i64 *cv = &y;
    i64 *cz = cv;
    if (x == 4) { cv = &x; }
    else { cv = null; }
    if (*cz == 7 && cv == null) { correct++; }

    /* Copy of a copy */
    i64 *c1 = &x;
    i64 *c2 = c1;
    i64 *c3 = c2;
    c1 = &y;
    c2 = null;
    if (*c3 == 3 && c2 == null && *c1 == 7) { correct++; }

    /* Integer copies were already right; keep them so */
    i64 ia = 1, ib = 2;
    i64 ic = ia;
    ia = ib;
    if (ic == 1 && ia == 2) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
