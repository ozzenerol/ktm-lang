/* At the top of the tree, so not part of any module: the program */
import geo;
import net.http;

i32 main() {
  geo.Point p;
  p.x = geo.SIZE;
  printf("%d %d %d\n", geo.area(3), http.get(2), p.x);
  return 0;
}
