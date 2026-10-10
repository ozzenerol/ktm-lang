I64 Sum(...)
{
  I64 t = 0;
  for (I64 i = 0; i < argc; i++) t += argv[i](I64);
  return t;
}
F64 First(U8 *tag, ...) { if (argc) return argv[0](F64); return 0; }
I64 Join(I64 n, I64 *v) { return n + v[0]; }
I64 Fwd(...) { return Join(argc, argv); }
U0 Main()
{
  "%d %.1f %d\n", Sum(1, 2, 3), First("x", 2.5), Fwd(7, 8);
}
