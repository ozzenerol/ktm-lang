#include "testhelper.ktm"

import str;

/* A function returning a function pointer, declared C's way,
 * `i64 (*GetH())(i64) { ... }`, was a syntax error. Now: definitions and
 * prototypes, with parameters, old and new type names, void and pointer
 * returns of the pointed-to function, and `(**Name(...))(...)` returning
 * a pointer to a function pointer. */

i64 twice(i64 x) { return x * 2; }
i64 thrice(i64 x) { return x * 3; }
u8 gLast[16];
void hello(u8 *who) { str.copy(gLast, who); }
u8 *echo(u8 *s) { return s; }

fn(i64) i64 getH() { return &twice; }

fn(i64) i64 pick(i64 which);          /* prototype first */
fn(i64) i64 pick(i64 which) {
    if (which) { return &thrice; }
    return &twice;
}

fn(i64) i64 pickNew(bool b, i64 unused) {
    if (b) { return &thrice; }
    return &twice;
}

fn(u8 *who) void greeter() { return &hello; }
fn(u8 *) u8 *echoer() { return &echo; }

fn(i64) i64 gFp = null;
(fn(i64) i64) *addrOfG() { return &gFp; }

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - functions returning function pointers: ");

    tests++;
    fn(i64) i64 h = getH();
    fn(i64) i64 p1 = pick(1);
    fn(i64) i64 p0 = pick(0);
    fn(i64) i64 p2 = pickNew(false, 9);
    if (h(7) == 14 && p1(5) == 15 && p0(5) == 10 && p2(4) == 8) { correct++; }
    else { printf("\n  returned pointers\n"); }

    tests++;
    fn(u8 *) void gr = greeter();
    gr("ktm");
    fn(u8 *) u8 *ec = echoer();
    if (!str.cmp(gLast, "ktm") && !str.cmp(ec("x"), "x")) { correct++; }
    else { printf("\n  void and pointer returns\n"); }

    tests++;   /* a pointer to a function pointer, written through */
    (fn(i64) i64) *pg = addrOfG();
    *pg = &thrice;
    if (gFp(10) == 30 && pg == &gFp) { correct++; }
    else { printf("\n  pointer to function pointer return\n"); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
