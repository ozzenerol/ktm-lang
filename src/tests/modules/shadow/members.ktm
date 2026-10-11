import geo;
struct Box { i64 one; i64 n; };
i64 Box.get() { return self.n; }
i64 f(Box *geo) { return geo.one + geo.get(); }
i32 main() {
  Box b; b.one = 40; b.n = 2;
  i64 x = geo.one();
  {
    Box geo = b;
    x += geo.one + geo.get();
  }
  printf("%d %d %d\n", x, f(&b), geo.one());
  return 0;
}
