class Shape { U8 *name; F64 (*area)(Shape *s); };
I64 Twice(I64 x) { return 2 * x; }
I64 Sq(I64 x) { return x * x; }
I64 Apply(I64 (*f)(I64), I64 x) { return f(x); }
I64 (*Pick(Bool sq))(I64) { if (sq) return &Sq; return &Twice; }
U8 *(*g_namer)(U8 *s, I64 (*cb)(I64)) = NULL;
U0 Main()
{
  I64 (*ops[2])(I64);
  ops[0] = &Twice;
  I64 (**pp)(I64) = &ops[0];
  U0 *raw = &Sq;
  I64 r = raw(I64 (*)(I64))(6);
  "%d %d %d\n", Apply(ops[0], 3), (*pp)(4), Pick(TRUE)(5);
}
