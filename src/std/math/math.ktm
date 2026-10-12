module math;
import libc;

#ifdef IS_X86_64
asm {
_ABS::
        MOV     RAX, RDI
        NEG     RAX
        CMOVL   RAX, RDI /* will discard the negate if the value was greater than 0 */
        RET
}

/* The absolute value of an integer, as an i64. Abs(I64_MIN) is I64_MIN
 * because 2^63 does not fit, like C's labs. For an f64 use fabs. */
pub _extern _ABS i64 abs(i64 num);
#endif

#ifdef IS_ARM_64
pub i64 abs(i64 num) {
    if (num < 0) { return -num; }
    return num;
}

#endif

/* Greatest common divisor (Euclid). Negative inputs use their absolute
 * values and the result is never negative: Gcd(0, n) == |n| and
 * Gcd(0, 0) == 0. Gcd(I64_MIN, 0) is 2^63, which only fits as a u64.
 * The loop runs on non-positive values because -|x| always fits in an
 * i64 while |I64_MIN| does not. */
/* The smaller and the larger of two integers */
pub i64 min(i64 a, i64 b) { return a < b ? a : b; }

pub i64 max(i64 a, i64 b) { return a > b ? a : b; }

/* v kept within lo..hi (lo when v < lo, hi when v > hi) */
pub i64 clamp(i64 v, i64 lo, i64 hi)
{
    if (v < lo) { return lo; }
    return v > hi ? hi : v;
}

pub u64 gcd(i64 a, i64 b) {
    i64 t;
    if (a > 0) { a = -a; }
    if (b > 0) { b = -b; }
    while (b != 0) {
        if (b == -1) { return 1; } /* I64_MIN % -1 traps in IDIV */
        t = a % b;
        a = b;
        b = t;
    }
    return -a;
}

/* `a` to the power `b`. An integral `b` in the i64 range is done by
 * repeated squaring, so Pow(2, 10) is exactly 1024.0; any other `b`
 * (fractional, NaN, infinite or beyond i64) goes to pow. */
pub f64 power(f64 a, f64 b) {
    if (b != libc.trunc(b) || b < -9.223372036854775808e18 ||
            b >= 9.223372036854775808e18) {
        return libc.pow(a, b);
    }
    if (b == 0) { return 1.0; }
    f64 result = 1.0;
    i64 e = b;
    u64 n = e; /* unsigned so that -I64_MIN still fits */
    if (e < 0) {
        a = 1 / a;
        n = -n;
    }
    while (n > 0) {
        if (n & 1) {
            result = result * a;
        }
        a = a * a;
        n >>= 1;
    }
    return result;
}

pub u64 divModU64(u64 *k, u64 m)
{// returns *k % m and sets *k to *k / m.
    u64 res = *k % m;
    *k /= m;
    return res;
}

/* ---- bit operations ----
 * rotl/rotr/bswap/clz/ctz are compiled to one instruction where the call
 * is written (rol/ror/bswap/bsr/bsf on x86_64, ror/rev/clz/rbit on
 * arm64); the bodies below are what `&math.rotl` and the like point to,
 * with the same results. Pass a u32 or a u64 (cast an i64 first): the
 * type picks the width. */

/* x rotated left (rotl) or right (rotr) by n bits, n taken mod the width */
pub u32 rotl(u32 x, i64 n) { i64 k = n & 31; return (x << k) | (x >> ((32 - k) & 31)); }
pub u64 rotl(u64 x, i64 n) { i64 k = n & 63; return (x << k) | (x >> ((64 - k) & 63)); }
pub u32 rotr(u32 x, i64 n) { i64 k = n & 31; return (x >> k) | (x << ((32 - k) & 31)); }
pub u64 rotr(u64 x, i64 n) { i64 k = n & 63; return (x >> k) | (x << ((64 - k) & 63)); }

/* x with its bytes in the other order (big-endian <-> little-endian) */
pub u16 bswap(u16 x) { return ((x & 0xFF) << 8) | (x >> 8); }
pub u32 bswap(u32 x) {
    return ((x & 0xFF) << 24) | ((x & 0xFF00) << 8) | ((x >> 8) & 0xFF00) | (x >> 24);
}
pub u64 bswap(u64 x) {
    u64 lo = bswap((u32)(x & 0xFFFFFFFF));
    u64 hi = bswap((u32)(x >> 32));
    return (lo << 32) | hi;
}

/* How many 0 bits lead (clz) or trail (ctz) x: the width (32 or 64) for 0 */
pub i64 clz(u32 x) {
    if (x == 0) { return 32; }
    i64 n = 0;
    while (!(x & 0x80000000)) { x <<= 1; n++; }
    return n;
}
pub i64 clz(u64 x) {
    if (x == 0) { return 64; }
    i64 n = 0;
    while (!(x & 0x8000000000000000)) { x <<= 1; n++; }
    return n;
}
pub i64 ctz(u32 x) {
    if (x == 0) { return 32; }
    i64 n = 0;
    while (!(x & 1)) { x >>= 1; n++; }
    return n;
}
pub i64 ctz(u64 x) {
    if (x == 0) { return 64; }
    i64 n = 0;
    while (!(x & 1)) { x >>= 1; n++; }
    return n;
}

/* How many 1 bits x has */
pub i64 popcount(u64 x) {
    x = x - ((x >> 1) & 0x5555555555555555);
    x = (x & 0x3333333333333333) + ((x >> 2) & 0x3333333333333333);
    x = (x + (x >> 4)) & 0x0F0F0F0F0F0F0F0F;
    return (x * 0x0101010101010101) >> 56;
}
pub i64 popcount(u32 x) { return popcount((u64)x); }
