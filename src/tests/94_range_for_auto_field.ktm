import test;

import mem, vec;

/* `auto` in a range-based for over a container reached through a class
 * field (`h.v`, `p->v`, `a.b->v`) was given the type of the container's
 * `entries` pointer instead of the element type, so `s += it` did pointer
 * arithmetic and an IntVec of 10, 20, 30 summed to 830 instead of 60.
 * A container held by value in a field (`w.c`, `wp->c`) was dereferenced
 * as if it were a pointer. */

struct Container {
    i64 size;
    i64 *entries;
};

struct FContainer {
    f64 *entries;
    i64 size;
};

struct ByValue {
    i64 pad;
    Container c;
    FContainer fc;
};

struct StaticContainer {
    i64 entries[4];
    i64 size;
};

struct Holder {
    vec.IntVec *v;
    vec.FloatVec *fv;
    vec.PtrVec *pv;
    StaticContainer *sc;
};

struct Outer {
    i64 pad;
    Holder *h;
};

struct BoxInt {
    i64 i;
};

BoxInt *bInt(i64 i) {
    BoxInt *b = mem.alloc(sizeof(BoxInt));
    b.i = i;
    return b;
}

i32 main() {
    i64 correct = 0, tests = 20, sum;
    f64 fsum;

    printf("Test - range-based for with auto over a class field: ");

    Holder h;
    h.v = vec.intNew();
    h.v.push(10);
    h.v.push(20);
    h.v.push(30);

    /* IntVec through `h.field`. */
    sum = 0;
    for (auto it : h.v) { sum += it; }
    if (sum == 60) { correct++; }

    /* Brace-less body. */
    sum = 0;
    for (auto it : h.v) { sum += it; }
    if (sum == 60) { correct++; }

    /* The element type is i64, so it is 8 bytes and not a pointer. */
    sum = 0;
    for (auto it : h.v) { sum += sizeof(it) + (it + 1) - it; }
    if (sum == 27) { correct++; }

    /* IntVec through `p->field`. */
    Holder *hp = &h;
    sum = 0;
    for (auto it : hp.v) { sum += it; }
    if (sum == 60) { correct++; }

    /* Nested fields: `o.h->v` and `op->h->v`. */
    Outer o;
    o.h = hp;
    sum = 0;
    for (auto it : o.h.v) { sum += it; }
    if (sum == 60) { correct++; }

    Outer *op = &o;
    sum = 0;
    for (auto it : op.h.v) { sum += it; }
    if (sum == 60) { correct++; }

    /* Typed loop variable through a field keeps working. */
    sum = 0;
    for (i64 it : h.v) { sum += it; }
    if (sum == 60) { correct++; }

    /* FloatVec: the element type is f64. */
    h.fv = vec.floatNew();
    h.fv.push(0.5);
    h.fv.push(1.25);
    h.fv.push(2.0);
    fsum = 0.0;
    for (auto f : h.fv) { fsum += f; }
    if (fsum == 3.75) { correct++; }

    fsum = 0.0;
    for (auto f : hp.fv) { fsum += f * 2.0; }
    if (fsum == 7.5) { correct++; }

    /* PtrVec: the element type is a pointer, not a pointer to a pointer. */
    h.pv = vec.ptrNew();
    h.pv.push(bInt(1));
    h.pv.push(bInt(4));
    h.pv.push(bInt(8));
    sum = 0;
    for (auto p : hp.pv) {
        BoxInt *b = p;
        sum += b.i;
    }
    if (sum == 13) { correct++; }

    /* A class field whose `entries` is an array: only `size` elements. */
    h.sc = mem.alloc(sizeof(StaticContainer));
    h.sc.size = 0;
    h.sc.entries[h.sc.size++] = 5;
    h.sc.entries[h.sc.size++] = 6;
    h.sc.entries[2] = 100;
    h.sc.entries[3] = 1000;
    sum = 0;
    for (auto it : h.sc) { sum += it; }
    if (sum == 11) { correct++; }

    /* The array itself through a field: all 4 elements. */
    sum = 0;
    for (auto it : hp.sc.entries) { sum += it; }
    if (sum == 1111) { correct++; }

    /* Containers held by value in a field, through `.` and `->`. */
    i64 buf[3] = {1, 2, 3};
    f64 fbuf[2] = {0.25, 0.5};
    ByValue w;
    w.pad = 99;
    w.c.size = 3;
    w.c.entries = buf;
    w.fc.size = 2;
    w.fc.entries = fbuf;
    ByValue *wp = &w;

    sum = 0;
    for (auto it : w.c) { sum += it; }
    if (sum == 6) { correct++; }

    sum = 0;
    for (auto it : w.c) { sum += it; }
    if (sum == 6) { correct++; }

    sum = 0;
    for (i64 it : w.c) { sum += it; }
    if (sum == 6) { correct++; }

    sum = 0;
    for (auto it : wp.c) { sum += it; }
    if (sum == 6) { correct++; }

    sum = 0;
    for (i64 it : wp.c) { sum += it; }
    if (sum == 6) { correct++; }

    fsum = 0.0;
    for (auto f : w.fc) { fsum += f; }
    if (fsum == 0.75) { correct++; }

    fsum = 0.0;
    for (f64 f : wp.fc) { fsum += f; }
    if (fsum == 0.75) { correct++; }

    /* The loop writes nothing back into the class. */
    if (w.pad == 99 && w.c.size == 3 && w.c.entries == buf) { correct++; }

    for (BoxInt *b : h.pv) { mem.free(b); }
    mem.free(h.sc);
    h.pv.release();
    h.fv.release();
    h.v.release();

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
