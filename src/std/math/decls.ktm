module math;

#ifdef IS_X86_64
/* The absolute value of an integer, as an I64. Abs(I64_MIN) is I64_MIN
 * because 2^63 does not fit, like C's labs. For an F64 use fabs. */
public _extern _ABS I64 Abs(I64 num);
#endif

#ifdef IS_ARM_64
public I64 Abs(I64 num);
#endif
