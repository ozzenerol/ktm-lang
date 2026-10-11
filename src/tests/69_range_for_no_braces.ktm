import test;

import mem, vec;

/* A range-based for loop whose body is a single statement without braces
 * crashed the compiler: the body was assumed to be a compound statement
 * and the loop variable's declaration was prepended to its statement
 * list. */

struct Container {
    i64 *entries;
    i64 size;
};

struct StaticContainer {
    i64 entries[4];
    i64 size;
};

struct Holder {
    vec.IntVec *v;
};

struct BoxInt {
    i64 i;
};

BoxInt *bInt(i64 i) {
    BoxInt *b = mem.alloc(sizeof(BoxInt));
    b.i = i;
    return b;
}

i64 gAcc = 0;

void accum(i64 v) {
    gAcc += v;
}

i32 main() {
    i64 correct = 0, tests = 17, i;

    printf("Test - range-based for without braces: ");

    i64 arr[] = {1, 2, 3, 4};
    i64 sum = 0;

    /* Plain statement body over a fixed-size array. */
    for (auto v : arr) { sum += v; }
    if (sum == 10) { correct++; }

    /* Print statement body. */
    i64 small[] = {7, 8, 9};
    for (auto v : small) { printf("%d", v); }
    printf(" ");
    correct++;

    /* `if` statement body. */
    sum = 0;
    for (auto v : arr) { if (v % 2 == 0) { sum += v; } }
    if (sum == 6) { correct++; }

    /* `if`/`else` body containing break. */
    i64 n = 0;
    for (auto v : arr) { if (v == 3) { break; } else { n++; } }
    if (n == 2) { correct++; }

    /* `break` and `continue` as the whole body. */
    n = 0;
    for (auto v : arr) { break; }
    for (auto v : arr) { continue; }
    correct++;

    /* `if` with continue keeps iterating. */
    sum = 0;
    for (auto v : arr) { if (v % 2) { continue; } else { sum += v; } }
    if (sum == 6) { correct++; }

    /* Nested brace-less range loops. */
    sum = 0;
    for (auto a : arr) { for (auto b : arr) { sum += a * b; } }
    if (sum == 100) { correct++; }

    /* Function call body. */
    gAcc = 0;
    for (auto v : arr) { accum(v); }
    if (gAcc == 10) { correct++; }

    /* f64 array. */
    f64 farr[] = {0.5, 1.5, 2.0};
    f64 fsum = 0.0;
    for (auto f : farr) { fsum += f; }
    if (fsum == 4.0) { correct++; }

    /* Pointer to a class with `entries` and `size`. */
    Container *c = mem.alloc(sizeof(Container));
    c.entries = mem.alloc(sizeof(i64) * 4);
    c.size = 0;
    c.entries[c.size++] = 11;
    c.entries[c.size++] = 22;
    c.entries[c.size++] = 33;
    sum = 0;
    for (auto it : c) { sum += it; }
    if (sum == 66) { correct++; }
    mem.free(c.entries);
    mem.free(c);

    /* Class whose `entries` is an array. */
    StaticContainer *sc = mem.alloc(sizeof(StaticContainer));
    sc.size = 0;
    sc.entries[sc.size++] = 5;
    sc.entries[sc.size++] = 6;
    sc.entries[2] = 100;
    sc.entries[3] = 1000;
    sum = 0;
    for (auto it : sc) { sum += it; }
    if (sum == 11) { correct++; }

    /* Array field reached through a class reference: all 4 elements. */
    sum = 0;
    for (auto it : sc.entries) { sum += it; }
    if (sum == 1111) { correct++; }
    mem.free(sc);

    /* IntVec, directly and through a class reference. */
    vec.IntVec *iv = vec.intNew();
    iv.push(10);
    iv.push(20);
    iv.push(30);
    sum = 0;
    for (auto it : iv) { sum += it; }
    if (sum == 60) { correct++; }

    Holder h;
    h.v = iv;
    sum = 0;
    for (i64 it : h.v) { sum += it; }
    if (sum == 60) { correct++; }
    iv.release();

    /* PtrVec with a typed loop variable. */
    vec.PtrVec *pv = vec.ptrNew();
    pv.push(bInt(1));
    pv.push(bInt(4));
    pv.push(bInt(8));
    sum = 0;
    for (BoxInt *b : pv) { sum += b.i; }
    if (sum == 13) { correct++; }
    for (BoxInt *b : pv) { mem.free(b); }
    pv.release();

    /* An empty body: `;` parses to no statement at all. */
    for (auto v : arr) {}
    for (f64 f : farr) {}
    correct++;

    /* Ordinary brace-less for loops are unchanged. */
    sum = 0;
    for (i = 0; i < 4; i++) { sum += arr[i]; }
    if (sum == 10) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
