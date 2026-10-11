#include "testhelper.ktm"

import mem;

/* Reading an element of an array of `i64 *` / `i32 *` / `f64 *` (or any
 * pointer to a non-u8, non-class type), or dereferencing an `i64 **`,
 * returned the address of the element instead of loading it: `*pb[0]`
 * printed an address and `i64 *t = pb[0]` gave `&pb`. `&p` on a local
 * `i64 *p` returned p's value instead of its address, which hid the bug
 * for `i64 **pp = &p; **pp` but broke `*pp = ...` and out-parameters. */

struct Holder {
    i64 *ptrs[3];
    f64 *fptrs[2];
    i64 n;
};

struct Pt {
    i64 x, y;
};

i64 gA = 11, gB = 22;
i64 *gArr[2];
i64 *gP = &gA;
i64 **gPp = &gP;

void setOut(i64 **out) { *out = &gB; }
i64 readPP(i64 **pp) { return **pp; }
i64 sumAll(i64 **ptrs, i64 n) {
    i64 i, s = 0;
    for (i = 0; i < n; i++) { s += *ptrs[i]; }
    return s;
}

i32 main() {
    i64 correct = 0, tests = 30, i;

    printf("Test - pointer array element load: ");

    /* Every pointer element type, local array. */
    i8 v8 = -8; u8 vu8 = 200; i16 v16 = -1600; u16 vu16 = 60000;
    i32 v32 = -320000; u32 vu32 = 4000000000; i64 v64 = 5; u64 vu64 = 77;
    f64 vf = 1.5;
    i8 *p8[2]; u8 *pu8[2]; i16 *p16[2]; u16 *pu16[2];
    i32 *p32[2]; u32 *pu32[2]; i64 *p64[2]; u64 *pu64[2]; f64 *pf[2];
    p8[1] = &v8; pu8[1] = &vu8; p16[1] = &v16; pu16[1] = &vu16;
    p32[1] = &v32; pu32[1] = &vu32; p64[1] = &v64; pu64[1] = &vu64;
    pf[1] = &vf;
    if (*p8[1] == -8 && *pu8[1] == 200) { correct++; }
    if (*p16[1] == -1600 && *pu16[1] == 60000) { correct++; }
    if (*p32[1] == -320000 && *pu32[1] == 4000000000) { correct++; }
    if (*p64[1] == 5 && *pu64[1] == 77) { correct++; }
    if (*pf[1] == 1.5) { correct++; }

    /* The element itself is the stored pointer, not the array's address. */
    i64 *t = p64[1];
    if (t == &v64 && t != &p64) { correct++; }
    if (p32[1] == &v32 && pf[1] == &vf) { correct++; }

    /* Variable index and writes through an element. */
    i64 b0 = 1, b1 = 2, b2 = 3;
    i64 *pb[3];
    pb[0] = &b0; pb[1] = &b1; pb[2] = &b2;
    i64 sum = 0;
    for (i = 0; i < 3; i++) { sum += *pb[i]; }
    if (sum == 6) { correct++; }
    *pb[0] = 7;
    *pb[2] += 10;
    (*pb[1])++;
    if (b0 == 7 && b1 == 3 && b2 == 13) { correct++; }
    pb[1] = pb[2];
    if (*pb[1] == 13) { correct++; }
    if (&pb[1] - &pb[0] == 1 && *(&pb[2])[0] == 13) { correct++; }
    *pf[1] = 2.25;
    if (vf == 2.25) { correct++; }

    /* Pointer-to-pointer. */
    i64 a = 5, b = 6;
    i64 *p = &a;
    i64 **pp = &p;
    if (**pp == 5 && *pp == &a && pp != p) { correct++; }
    *pp = &b;
    if (p == &b && *p == 6 && a == 5) { correct++; }
    **pp = 60;
    if (b == 60) { correct++; }
    i64 *r = null;
    setOut(&r);
    if (r == &gB && *r == 22) { correct++; }
    if (readPP(&p) == 60) { correct++; }
    i64 **heap = mem.alloc(sizeof(i64 *) * 2);
    heap[0] = &a;
    heap[1] = &b;
    if (*heap[0] == 5 && *heap[1] == 60 && sumAll(heap, 2) == 65) { correct++; }
    mem.free(heap);
    if (sumAll(pb, 3) == 33) { correct++; }

    /* Arrays of pointers to pointers. */
    i64 **ppa[2];
    ppa[0] = &p;
    if (**ppa[0] == 60 && *ppa[0] == &b) { correct++; }

    /* `void *` elements. */
    void *vp[2];
    vp[0] = &a;
    vp[1] = &vf;
    i64 *back = vp[0];
    f64 *fback = vp[1];
    if (back == &a && *back == 5 && *fback == 2.25) { correct++; }

    /* Class pointer and u8 pointer elements unchanged. */
    Pt pt;
    pt.x = 3; pt.y = 4;
    Pt *pts[1];
    pts[0] = &pt;
    u8 *strs[2];
    strs[0] = "hi";
    if (pts[0].y == 4 && strs[0][1] == 'i' && *strs[0] == 'h') { correct++; }

    /* Global array of pointers and a global pointer-to-pointer. */
    gArr[0] = &gA;
    gArr[1] = &gB;
    if (*gArr[0] == 11 && *gArr[1] == 22) { correct++; }
    *gArr[1] = 23;
    if (gB == 23 && gArr[1] == &gB) { correct++; }
    if (**gPp == 11 && *gPp == &gA) { correct++; }

    /* Pointer arrays as class fields, by value and through a pointer. */
    Holder h;
    Holder *hp = &h;
    h.ptrs[0] = &a; h.ptrs[1] = &b; h.ptrs[2] = &b0;
    h.fptrs[0] = &vf;
    if (*h.ptrs[0] == 5 && *h.ptrs[2] == 7) { correct++; }
    if (*hp.ptrs[1] == 60 && *hp.fptrs[0] == 2.25) { correct++; }
    *hp.ptrs[0] = 50;
    i64 *hx = hp.ptrs[0];
    if (a == 50 && hx == &a) { correct++; }

    /* `&p` of a pointer local is its address. */
    i64 **addr = &p;
    if (addr != p && *addr == p) { correct++; }

    /* `*&x` and `&*p` still cancel. */
    if (*&a == 50 && &*p == p) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
