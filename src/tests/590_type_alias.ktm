import reflect, str, test;

/* `type Name = T;` (v0.9.0): an alias is its type. Slices, fn types,
 * fixed arrays, pointers, structs and aliases of aliases; values pass
 * between an alias and its type with no conversion; `type` stays usable
 * as a name; reflect sees the real type. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

type Text = u8[];
type Handler = fn(i64, Text) i64;
type Buf = u8[16];
struct Point { i64 x, y; };
type P = Point;
type PPtr = P *;
type Count = i64;
type Total = Count;
type Points = Point[];
pub type Flag = bool;

i64 weigh(i64 n, u8[] s) { return n * 100 + s.len; }

Total sum(Points ps) {
    Total t = 0;
    for (i64 i = 0; i < ps.len; i++) { t += ps[i].x + ps[i].y; }
    return t;
}

Text greet(Text who) { return who[0:2]; }

struct Holder { Text name; Count n; Handler h; };

i32 main() {
    printf("Test - type aliases: ");

    Text t = "hello";
    u8[] plain = t;
    check(t.len == 5 && str.equals(plain, "hello"), "Text is u8[]");
    check(str.equals(greet("world"), "wo"), "an alias as a parameter and return type");

    Handler h = &weigh;
    check(h(3, "ab") == 302, "a fn type alias");
    fn(i64, u8[]) i64 same = h;
    check(same(1, "") == 100, "the alias and the fn type are one type");

    Buf b;
    b[15] = 9;
    check(sizeof(Buf) == 16 && sizeof(b) == 16 && b[15] == 9, "a fixed array alias");

    P p = {3, 4};
    PPtr pp = &p;
    Point *raw = pp;
    check(pp.x + pp.y == 7 && raw == &p && sizeof(P) == sizeof(Point), "struct and pointer aliases");

    Point arr[3] = {{1, 2}, {3, 4}, {5, 6}};
    check(sum(arr) == 21, "an alias of a slice of structs, an alias of an alias");

    Count c = 41;
    Total tt = c + 1;
    i64 back = tt;
    check(back == 42 && sizeof(Total) == 8, "Total = Count = i64");

    Holder hd = {"box", 7, &weigh};
    check(str.equals(hd.name, "box") && hd.h(hd.n, hd.name) == 703, "aliases as field types");

    Flag f = true;
    check(f && sizeof(Flag) == 1, "pub type in a program");

    check(reflect.typeOf(Count).kind == reflect.Kind.int && reflect.typeOf(Text).kind == reflect.Kind.slice,
          "reflect sees the real type");

    i64 type = 3;
    type = type * 2;
    check(type == 6, "`type` is still a name for a variable");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
