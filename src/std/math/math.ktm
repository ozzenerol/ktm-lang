module math;

#ifdef IS_X86_64
asm {
_ABS::
    MOV     RAX, RDI
    NEG     RAX
    CMOVL   RAX, RDI /* will discard the negate if the value was greater than 0 */
    RET
}

/* The absolute value of an integer, as an I64. Abs(I64_MIN) is I64_MIN
 * because 2^63 does not fit, like C's labs. For an F64 use fabs. */
public _extern _ABS I64 Abs(I64 num);
#endif

#ifdef IS_ARM_64
public I64 Abs(I64 num)
{
  if (num < 0) return -num;
  return num;
}

#endif

/* Greatest common divisor (Euclid). Negative inputs use their absolute
 * values and the result is never negative: Gcd(0, n) == |n| and
 * Gcd(0, 0) == 0. Gcd(I64_MIN, 0) is 2^63, which only fits as a U64.
 * The loop runs on non-positive values because -|x| always fits in an
 * I64 while |I64_MIN| does not. */
public U64 Gcd(I64 a, I64 b)
{
  I64 t;
  if (a > 0) a = -a;
  if (b > 0) b = -b;
  while (b != 0) {
    if (b == -1) return 1; /* I64_MIN % -1 traps in IDIV */
    t = a % b;
    a = b;
    b = t;
  }
  return -a;
}

/* `a` to the power `b`. An integral `b` in the I64 range is done by
 * repeated squaring, so Pow(2, 10) is exactly 1024.0; any other `b`
 * (fractional, NaN, infinite or beyond I64) goes to pow. */
public F64 Pow(F64 a, F64 b)
{
  if (b != trunc(b) || b < -9.223372036854775808e18 ||
      b >= 9.223372036854775808e18) {
    return pow(a, b);
  }
  if (b == 0) return 1.0;
  F64 result = 1.0;
  I64 e = b;
  U64 n = e; /* unsigned so that -I64_MIN still fits */
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

public U64 ModU64(U64 *k, U64 m)
{// returns *k % m and sets *k to *k / m.
  U64 res = *k % m;
  *k /= m;
  return res;
}
