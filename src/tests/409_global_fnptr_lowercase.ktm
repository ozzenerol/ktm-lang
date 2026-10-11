#include "testhelper.ktm"

import str;

/* A global function pointer (or array of them) whose return type is a
 * lowercase built-in type or a class name, `i64 (*g)(i64);`, was taken
 * for a top-level call of `i64` and failed ("Variable or function `g` has
 * not been defined"); `i64 (*g)(i64);` worked because i64 is a keyword.
 * A type name before `(` at file scope now starts a declaration; a real
 * top-level call `Foo(...);` is still a call. */

struct Pt {
    i64 x, y;
};

i64 twice(i64 x) { return x * 2; }
f64 half(f64 x) { return x / 2; }
u8 *Name() { return "ktm"; }
Pt origin() { Pt p; p.x = 1; p.y = 2; return p; }

fn(i64) i64 gInt;
fn(f64) f64 gFlt;
fn() u8 *gStr;
fn(i64) bool gNone = null;
fn() Pt gPt;
fn(i64) i64 gTbl[2];

i64 gCalls = 0;
void count(i64 n) { gCalls += n; }
                                    /* a top-level call, as before */

i32 main() {
    count(3);
    i64 correct = 0, tests = 0;
    printf("Test - global function pointers with lowercase types: ");

    tests++;
    gInt = &twice;
    gFlt = &half;
    gStr = &Name;
    if (gInt(21) == 42 && gFlt(5.0) == 2.5 && !str.cmp(gStr(), "ktm") && !gNone) { correct++; }
    else { printf("\n  scalars\n"); }

    tests++;
    gPt = &origin;
    Pt p = gPt();
    if (p.x == 1 && p.y == 2) { correct++; }
    else { printf("\n  class return\n"); }

    tests++;
    gTbl[0] = &twice;
    gTbl[1] = &twice;
    if (gTbl[0](1) + gTbl[1](2) == 6) { correct++; }
    else { printf("\n  array\n"); }

    tests++;
    if (gCalls == 3) { correct++; }
    else { printf("\n  top-level call: %d\n", gCalls); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
