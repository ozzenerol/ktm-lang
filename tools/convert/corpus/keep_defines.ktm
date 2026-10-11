// keep-defines: a test of #define keeps its macros, in strings too
#define X (1)
#define NAME "n"
U8 *prog = "#define Y 2\nI64 F() { return Y; }\n";
U0 Main()
{
  "%d %s\n", X, NAME;
}
