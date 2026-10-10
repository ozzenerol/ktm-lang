// A sample in the old syntax
#define LIMIT 3

class Point
{
  I64 x, y;   /* coordinates */
};

public U8 *Name(Point *p)
{
  if (p == NULL) return "none";
  return "pt";
}

F64 Half(I64 v) { return v(F64) / 2; }

I64 Counter() { static I64 n = 0; return ++n; }

U0 Main()
{
  Point p;
  Bool ok = TRUE;
  I64 raw = 0x4142;
  U8 *bytes = &raw;
  p.x = 3; p.y = 4;
  "%s %d %d\n", Name(&p), p.x, p.y;
  "%.1f %d\n", Half(5), (p.x + p.y)(F64)(I64);
  "%c%c\n", bytes(U8 *)[0], bytes[1];
  'ok';
  "\n";
  Counter;
  "%d %d\n", Counter, ok == FALSE;
}
