I64 Twice(I64 v) { return v * 2; }
I64 g = Twice(21);   // runs at startup
"before main %d\n", g;

I32 Main(I32 argc, U8 **argv)
{
  "in main %d\n", argc;
  return 0;
}
