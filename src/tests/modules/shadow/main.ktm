import geo;
i64 twice(i64 geo) { return geo * 2; }
i32 main() {
  i64 a = geo.one();
  {
    u8 *geo = "x";
    a += geo[0] == 'x';
  }
  geo.Point p; p.x = 3;
  printf("%d %d %d\n", a, twice(4), p.x + geo.one());
  return 0;
}
