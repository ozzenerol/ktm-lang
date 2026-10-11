#include "testhelper.ktm"

/* `(p + k)[i]` and `p[i].field` address memory as base + index*scale
 * + a constant offset. The arm64 backends folded the offset into the
 * base register in place, so `(p + 1)[i] += 1` added it twice (the
 * store landed one element further) and a pointer parameter living
 * in that register was left pointing past where the caller put it. */

struct Pt {
    i64 x;
    f64 y;
    i32 z;
    i32 w;
};

void addOff(i64 a, i64 b, i64 *p, i64 i) { (p + 1)[i] += 1; }
void addOffFirst(i64 *p, i64 i) { (p + 1)[i] += 5; }
void subNeg(i64 *p, i64 i) { (p - 1)[i] -= 3; }
void addBig(i64 *p, i64 i) { (p + 1000)[i] += 9; }
void incU8Off(u8 *p, i64 i) { (p + 2)[i]++; }
void orI16Off(i16 *p, i64 i) { (p + 1)[i] |= 0x100; }
void addI32Off(i32 *p, i64 i) { (p + 1)[i] += 10; }
void addField(Pt *p, i64 i) { p[i].z += 4; }

i64 ldTwice(i64 *p, i64 i) { return (p + 1)[i] + (p + 1)[i]; }
i64 ldThenP(i64 *p, i64 i) { i64 r = (p + 1)[i]; return r + p[0]; }
i64 stThenP(i64 *p, i64 i, i64 v) { (p + 1)[i] = v; return p[0]; }
i64 stTwice(i64 *p, i64 i) { (p + 1)[i] = 7; (p + 1)[i + 1] = 8; return p[0]; }
i64 addThenP(i64 *p, i64 i) { (p + 1)[i] += 1; return p[0] + p[i]; }
i64 addTwice(i64 *p, i64 i) { (p + 1)[i] += 1; (p + 1)[i] += 1; return p[1]; }
i32 fieldTwice(Pt *p, i64 i) { p[i].z = p[i].w + p[i].z; return p[0].w; }

f64 ldF64Off(f64 *p, i64 i) { return (p + 1)[i]; }
void stF64Off(f64 *p, i64 i, f64 v) { (p + 1)[i] = v; }
f64 ldField(Pt *p, i64 i) { return p[i].y; }
void stField(Pt *p, i64 i, f64 v) { p[i].y = v; }

i32 main() {
    i64 correct = 0, tests = 22;

    printf("Test - offset base plus index read-modify-write: ");

    i64 v[4] = {10, 20, 30, 40};
    i64 big[1004];
    u8 b[6] = {1, 2, 3, 4, 5, 6};
    i16 h[4] = {1, 2, 3, 4};
    i32 w[4] = {-1, -2, -3, -4};
    f64 f[4] = {0.5, 1.5, 2.5, 3.5};
    Pt pts[3];
    i64 k;
    for (k = 0; k < 1004; k++) { big[k] = k; }
    for (k = 0; k < 3; k++) {
        pts[k].x = k; pts[k].y = k + 0.25; pts[k].z = k * 10; pts[k].w = k * 100;
    }

    /* Read-modify-write */
    addOff(0, 0, v, 1);
    if (v[2] == 31 && v[3] == 40 && v[1] == 20) { correct++; }
    addOffFirst(v, 2);
    if (v[3] == 45 && v[2] == 31) { correct++; }
    subNeg(v, 1);
    if (v[0] == 7 && v[1] == 20) { correct++; }
    addBig(big, 2);
    if (big[1002] == 1011 && big[1003] == 1003 && big[1001] == 1001) { correct++; }
    incU8Off(b, 1);
    if (b[3] == 5 && b[4] == 5 && b[2] == 3) { correct++; }
    orI16Off(h, 1);
    if (h[2] == 0x103 && h[3] == 4 && h[1] == 2) { correct++; }
    addI32Off(w, 2);
    if (w[3] == 6 && w[2] == -3) { correct++; }
    addField(pts, 1);
    if (pts[1].z == 14 && pts[1].w == 100 && pts[2].x == 2) { correct++; }

    /* The pointer is still intact afterwards */
    v[0] = 10; v[1] = 20; v[2] = 30; v[3] = 40;
    if (ldTwice(v, 1) == 60) { correct++; }
    if (ldThenP(v, 2) == 50) { correct++; }
    if (stThenP(v, 0, 99) == 10 && v[1] == 99 && v[2] == 30) { correct++; }
    if (stTwice(v, 1) == 10 && v[2] == 7 && v[3] == 8 && v[1] == 99) { correct++; }
    v[1] = 20; v[2] = 30; v[3] = 40;
    if (addThenP(v, 1) == 30 && v[2] == 31) { correct++; }
    if (addTwice(v, 0) == 22 && v[2] == 31) { correct++; }
    if (fieldTwice(pts, 2) == 0 && pts[2].z == 220 && pts[2].w == 200) { correct++; }

    /* Floats */
    if (ldF64Off(f, 2) == 3.5) { correct++; }
    stF64Off(f, 1, 9.5);
    if (f[2] == 9.5 && f[1] == 1.5 && f[3] == 3.5) { correct++; }
    if (ldField(pts, 2) == 2.25) { correct++; }
    stField(pts, 1, 7.75);
    if (pts[1].y == 7.75 && pts[1].x == 1 && pts[1].z == 14) { correct++; }

    /* Nothing else was written through a wild pointer */
    if (v[0] == 10 && v[3] == 40) { correct++; }
    if (b[0] == 1 && b[5] == 6 && h[0] == 1 && w[0] == -1 && w[1] == -2) { correct++; }
    if (f[0] == 0.5 && pts[0].z == 0 && pts[0].y == 0.25) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
