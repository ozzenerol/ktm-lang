I64 Foo() { return 1; }
U0 Main()
{
  U64 a = &Foo(U64);
  U64 b = (&Foo)(U64);
  "%d\n", a == b;
}
