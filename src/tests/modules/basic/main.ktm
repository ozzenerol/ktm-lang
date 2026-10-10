import geo;

/* the program's own square: no clash with geo's */
i64 square(i64 n) { return -n; }

struct Shape { geo.Kind kind; geo.Point corner; };

i32 main() {
  geo.Point *p = geo.makePoint(3, 4);
  printf("%d %d\n", p->x, p->y);
  printf("%d %d\n", geo.area(geo.Kind.square, 5), geo.area(geo.Kind.circle, 2));
  i64 cells[geo.SIZE];
  Shape s;
  s.kind = geo.Kind.circle;
  s.corner.x = 7;
  printf("%d %d %d %d %d\n", geo.calls(), sizeof(cells) / 8, square(2), s.kind, s.corner.x);
  i64 (*f)(i64, i64) = &geo.makePoint;
  printf("%d\n", f != NULL);
  return 0;
}
