#include "testhelper.ktm"

/* Struct layout + the `alignof` operator. Sizes/alignments are checked
 * against the C ABI (a struct's alignment is its widest member's, and
 * its size is rounded up to that - not blindly to 8). */

struct Color     { u8 r; u8 g; u8 b; u8 a; };        /* size 4,  align 1 */
struct Rectangle { f32 x; f32 y; f32 w; f32 h; };    /* size 16, align 4 */
struct Mixed     { i8 a; i64 b; i8 c; };             /* size 24, align 8 */
struct S2        { i16 x; i16 y; };                  /* size 4,  align 2 */
struct S3        { i8 a; i32 b; };                   /* size 8,  align 4 */
struct S5        { i8 a; i8 b; i8 c; };              /* size 3,  align 1 */
struct Inner     { u8 a; u8 b; u8 c; u8 d; };        /* size 4,  align 1 */
struct Outer     { u8 x; Inner i; };                /* size 5,  align 1 */
struct Has8      { u8 x; i64 y; };                   /* size 16, align 8 */
struct OuterH    { u8 x; Has8 h; };                 /* size 24, align 8 */

bool scalarAlign() {
    i64 tests = 9, correct = 0;
    if (alignof(u8)  == 1) { correct++; }
    if (alignof(bool)== 1) { correct++; }
    if (alignof(i16) == 2) { correct++; }
    if (alignof(i32) == 4) { correct++; }
    if (alignof(i64) == 8) { correct++; }
    if (alignof(u64) == 8) { correct++; }
    if (alignof(f32) == 4) { correct++; }
    if (alignof(f64) == 8) { correct++; }
    if (alignof(u8 *)== 8) { correct++; }
    return correct == tests;
}

bool structSize() {
    i64 tests = 10, correct = 0;
    if (sizeof(Color)     == 4)  { correct++; }
    if (sizeof(Rectangle) == 16) { correct++; }
    if (sizeof(Mixed)     == 24) { correct++; }
    if (sizeof(S2)        == 4)  { correct++; }
    if (sizeof(S3)        == 8)  { correct++; }
    if (sizeof(S5)        == 3)  { correct++; }
    if (sizeof(Inner)     == 4)  { correct++; }
    if (sizeof(Outer)     == 5)  { correct++; }
    if (sizeof(Has8)      == 16) { correct++; }
    if (sizeof(OuterH)    == 24) { correct++; }
    return correct == tests;
}

bool structAlign() {
    i64 tests = 8, correct = 0;
    if (alignof(Color)     == 1) { correct++; }
    if (alignof(Rectangle) == 4) { correct++; }
    if (alignof(Mixed)     == 8) { correct++; }
    if (alignof(S2)        == 2) { correct++; }
    if (alignof(S3)        == 4) { correct++; }
    if (alignof(Inner)     == 1) { correct++; }
    if (alignof(Outer)     == 1) { correct++; }
    if (alignof(OuterH)    == 8) { correct++; }
    return correct == tests;
}

bool alignofExpr() {
    /* alignof works on an expression, not just a type name. */
    i64 tests = 3, correct = 0;
    Color  c;
    OuterH o;
    f64    d;
    if (alignof(c) == 1) { correct++; }
    if (alignof(o) == 8) { correct++; }
    if (alignof(d) == 8) { correct++; }
    return correct == tests;
}

bool sizeMultipleOfAlign() {
    /* Invariant: sizeof is always a multiple of alignof. */
    i64 tests = 4, correct = 0;
    if (sizeof(Mixed)  % alignof(Mixed)  == 0) { correct++; }
    if (sizeof(OuterH) % alignof(OuterH) == 0) { correct++; }
    if (sizeof(Outer)  % alignof(Outer)  == 0) { correct++; }
    if (sizeof(S3)     % alignof(S3)     == 0) { correct++; }
    return correct == tests;
}

i32 main() {
    printf("Test - alignof and struct layout:\n");
    i64 tests = 5, correct = 0;
    if (scalarAlign())         { correct++; }
    if (structSize())          { correct++; }
    if (structAlign())         { correct++; }
    if (alignofExpr())         { correct++; }
    if (sizeMultipleOfAlign()) { correct++; }
    PrintResult(correct,tests);
    printf("====\n");
    return 0;
}
