#include "testhelper.ktm"

/* Address-of a subscripted POINTER: `&ptr[i]` and `&ptr[i].field`.
 *
 * Regression for a parser bug: in the unary-operator path, a `[` subscript
 * only bound to the operand for array/class-ref types, not pointers - so
 * `&t[i]` (t a pointer) parsed as `(&t)[i]` = `*((&t)+i)`, indexing the
 * pointer variable's own address by sizeof(ptr) instead of computing
 * `t + i*sizeof(*t)`. For a pointer to a 24-byte struct that read adjacent
 * stack/heap and bus-errored. The fix also keeps consuming the postfix chain
 * after the subscript so `&t[i].x` is `&(t[i].x)`, not `(&t[i]).x`. */

struct Tile { i64 x, y; i32 type; };

bool addressOfElement() {
    Tile arr[4];
    Tile *t = arr;                       /* pointer to a 24-byte struct */
    for (i64 i = 0; i < 4; ++i) {
        Tile *it = &t[i];                  /* the bug: must be t + i*24 */
        it.x = i; it.y = i * 10; it.type = i * 100;
    }
    i64 c = 0;
    for (i64 i = 0; i < 4; ++i) {
        if (t[i].x == i && t[i].y == i * 10 && t[i].type == i * 100) { c++; }
    }
    return c == 4;
}

bool addressOfField() {
    Tile arr[3];
    Tile *t = arr;
    i64 *xp = &t[1].x;     *xp = 777;    /* &(t[1].x), not (&t[1]).x */
    i32 *tp = &t[2].type;  *tp = 55;
    return t[1].x == 777 && t[2].type == 55;
}

bool pointerToElement() {
    Tile arr[3];
    Tile *t = arr;
    Tile *p = &t[1];
    p.type = 42;
    p.x = 9;
    return t[1].type == 42 && t[1].x == 9;
}

bool scalarPointerIndex() {
    i64 a[5];
    i64 *p = a;
    for (i64 i = 0; i < 5; ++i) { i64 *e = &p[i]; *e = i * 7; }
    return p[0] == 0 && p[3] == 21 && p[4] == 28;
}

i32 main() {
    printf("Test - &ptr[i] / &ptr[i].field on pointers to structs:\n");
    i64 tests = 4, correct = 0;
    if (addressOfElement())   { correct++; }
    if (addressOfField())     { correct++; }
    if (pointerToElement())   { correct++; }
    if (scalarPointerIndex()) { correct++; }
    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
