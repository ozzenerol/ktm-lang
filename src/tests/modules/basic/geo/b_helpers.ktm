module geo;

i64 g_calls = 0;

i64 square(i64 n) {
  g_calls++;
  return n * n;
}

pub Point *makePoint(i64 x, i64 y) {
  Point *p = MAlloc(sizeof(Point));
  p->x = x;
  p->y = y;
  return p;
}

pub i64 calls() { return g_calls; }
