module c;

/* Libc's maths functions */
extern "c" F64 acos(F64 f1);

extern "c" F64 asin(F64 f1);

extern "c" F64 atan(F64 f1);

extern "c" F64 atan2(F64 f1, F64 f2);

extern "c" F64 cos(F64 f1);

extern "c" F64 sin(F64 f1);

extern "c" F64 tan(F64 f1);

extern "c" F64 acosh(F64 f1);

extern "c" F64 asinh(F64 f1);

extern "c" F64 atanh(F64 f1);

extern "c" F64 cosh(F64 f1);

extern "c" F64 sinh(F64 f1);

extern "c" F64 tanh(F64 f1);

extern "c" F64 exp(F64 f1);

extern "c" F64 exp2(F64 f1);

extern "c" F64 expm1(F64 f1);

extern "c" F64 log(F64 f1);

extern "c" F64 log10(F64 f1);

extern "c" F64 log2(F64 f1);

extern "c" F64 log1p(F64 f1);

extern "c" F64 logb(F64 f1);

extern "c" F64 modf(F64 f1, F64 *fptr);

extern "c" F64 ldexp(F64 f1, I32 i);

extern "c" F64 frexp(F64 f1, I32 *i);

extern "c" I32 ilogb(F64 f1);

extern "c" F64 scalbn(F64 f1, I32 i);

extern "c" F64 scalbln(F64 f1, I64 i);

extern "c" F64 fabs(F64 f1);

extern "c" F64 cbrt(F64 f1);

extern "c" F64 hypot(F64 f1, F64 f2);

extern "c" F64 pow(F64 f1, F64 f2);

extern "c" F64 sqrt(F64 f1);

extern "c" F64 erf(F64 f1);

extern "c" F64 erfc(F64 f1);

extern "c" F64 lgamma(F64 f1);

extern "c" F64 tgamma(F64 f1);

extern "c" F64 ceil(F64 f1);

extern "c" F64 floor(F64 f1);

extern "c" F64 nearbyint(F64 f1);

extern "c" F64 rint(F64 f1);

extern "c" I64 lrint(F64 f1);

extern "c" F64 round(F64 f1);

extern "c" I64 lround(F64 f1);

extern "c" F64 trunc(F64 f1);

extern "c" F64 fmod(F64 f1, F64 f2);

extern "c" F64 remainder(F64 f1, F64 f2);

extern "c" F64 remquo(F64 f1, F64 f2, I32 *ptr);

extern "c" F64 copysign(F64 f1, F64 f2);

extern "c" F64 nan(U8 *s);

extern "c" F64 nextafter(F64 f1, F64 f2);

extern "c" F64 fdim(F64 f1, F64 f2);

extern "c" F64 fmax(F64 f1, F64 f2);

extern "c" F64 fmin(F64 f1, F64 f2);

extern "c" F64 fma(F64 f1, F64 f2, F64 f3);
