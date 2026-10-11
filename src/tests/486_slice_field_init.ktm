import test;

/* A slice field or element in an initialiser list took two items, its
 * pointer and its length (brace elision treated the 16-byte slice as a
 * struct): `Path p = {"init", ps};` put "init" in name.ptr, ps's address
 * in name.len and left pts empty, and `{"one", gps[1:]}` was an error. A
 * slice now takes one item, converted as in an assignment (a string, an
 * array or a slice), in locals and globals, in structs and arrays. */

struct Point { i64 x, y; };
struct Path { u8[] name; Point[] pts; };
struct Tagged { i64 id; Point at; u8[] tag; };

Point gps[2] = {{7, 8}, {9, 10}};
u8[] gnames[3] = {"a", "bc", "def"};
Path gp = {"global", gps};
Path gpaths[2] = {{"one", gps}, {"two", gps[1:]}};
Tagged gt = {1, 2, 3, "elided"};

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 main() {
    printf("Test - slices in initialiser lists: ");

    Point ps[3] = {{1, 2}, {3, 4}, {5, 6}};
    Path p = {"init", ps};
    check(p.name.equals("init") && p.pts.len == 3 && p.pts[2].y == 6, "local struct");
    p.pts[1].y = 40;
    check(ps[1].y == 40, "a view of the array, not a copy");

    u8[] n = "x";
    Path c = {n, ps[0:2]};
    check(c.name.len == 1 && c.pts.len == 2, "slice values");

    check(gp.name.equals("global") && gp.pts.len == 2 && gp.pts[1].x == 9, "global struct");
    check(gnames[0].equals("a") && gnames[1].equals("bc") && gnames[2].equals("def"),
          "global array of slices");
    check(gpaths[0].name.equals("one") && gpaths[0].pts.len == 2 &&
          gpaths[1].name.equals("two") && gpaths[1].pts.len == 1 &&
          gpaths[1].pts[0].x == 9, "global array of structs");
    check(gt.id == 1 && gt.at.x == 2 && gt.at.y == 3 && gt.tag.equals("elided"),
          "global, with an elided struct before the slice");

    u8[] names[] = {"x", "yy", "zzz"};
    check(sizeof(names) / sizeof(u8[]) == 3 && names[0].len == 1 && names[1].len == 2 &&
          names[2].len == 3, "local array of slices");
    Path paths[2] = {{"p", ps}, {"q", ps[2:]}};
    check(paths[0].pts.len == 3 && paths[1].name.equals("q") && paths[1].pts.len == 1 &&
          paths[1].pts[0].y == 6, "local array of structs");
    Tagged t = {4, 5, 6, "local"};
    check(t.id == 4 && t.at.x == 5 && t.at.y == 6 && t.tag.equals("local"),
          "local, with an elided struct before the slice");
    u8[] short[2] = {"only"};
    check(short[0].len == 4 && short[1].len == 0 && !short[1].ptr, "the rest is empty");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
