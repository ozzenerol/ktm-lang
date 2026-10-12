import geo;

i32 main() {
    geo.Rect r = {2, 3};
    geo.Circle c = {2};
    printf("%d %d", geo.area(&r), geo.area(&c));
    r.grow(1);
    geo.Rect big = {10, 10};
    r.grow(&big);
    printf(" %dx%d", r.w, r.h);
    String a = geo.describe(7);
    String b = geo.describe("box");
    printf(" %s %s", a.cstr(), b.cstr());
    a.free();
    b.free();
    fn(geo.Circle *) i64 f = &geo.area;
    printf(" %d %d %d %d\n", f(&c), geo.scale(4), geo.inner(), geo.total(&r, &c));
    return 0;
}
