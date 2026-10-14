/* At the top of the tree, so not part of any module: the program */
import geo, web.route;

i32 main() {
    geo.Point p;
    p.x = geo.SIZE;
    printf("%d %d %d\n", geo.area(3), route.get(2), p.x);
    return 0;
}
