import cstr, json, libc, reflect, str, test, vec;

/* json.encode writes a vec.Vec<T> as a JSON array of its items (v0.13.2),
 * exactly as a T[] slice: numbers, Strings, structs with enums, nested
 * Vecs, an empty (zeroed) Vec, a Vec at the top level, indented output, a
 * Vec<u8> as a string (like u8[]), and an error that names the item. It
 * wrote the Vec's own fields before, {"data":5,"len":2,"cap":8}. A struct
 * of your own with data/len/cap fields stays an object: a Vec is known by
 * its type. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* got == want, or say what came instead */
void same(String got, u8 *want, u8 *what) {
    bool ok = str.equals(got, cstr.view(want));
    check(ok, what);
    if (!ok) { printf("    got:  %s\n    want: %s\n", got, want); }
}

enum Kind { tool, part };

struct Item {
    String name;
    Kind kind;
    i64 qty;
};

struct Order {
    i64 id;
    vec.Vec<i64> sizes;
    vec.Vec<String> tags;
    vec.Vec<Item> items;
    vec.Vec<vec.Vec<i64>> grid;
    vec.Vec<i64> none;
};

/* not the vec module's Vec: an object, field by field */
struct MyVec {
    i64 *data;
    i64 len;
    i64 cap;
};

struct Holder {
    i64 n;
    fn(i64) i64 f;
};

struct Bad {
    vec.Vec<Holder> items;
};

i64 twice(i64 x) { return x * 2; }

Item item(u8 *name, Kind kind, i64 qty) {
    Item it;
    it.name = str.from(cstr.view(name));
    it.kind = kind;
    it.qty = qty;
    return it;
}

i32 main() {
    printf("Test - json.encode of a vec.Vec: ");

    Order o;
    libc.memset(&o, 0, sizeof(Order));    /* o.none stays a zeroed Vec */
    o.id = 7;
    o.sizes = vec.new<i64>();
    o.sizes.push(5);
    o.sizes.push(6);
    o.tags = vec.new<String>();
    o.tags.push(str.from("red"));
    o.tags.push(str.from("big"));
    o.items = vec.new<Item>();
    o.items.push(item("hammer", Kind.tool, 1));
    o.items.push(item("nail", Kind.part, 40));
    o.grid = vec.new<vec.Vec<i64>>();
    for (i64 r = 0; r < 2; r++) {
        vec.Vec<i64> row = vec.new<i64>();
        for (i64 c = 0; c < 3; c++) { row.push(r * 3 + c); }
        o.grid.push(row);
    }

    String s = json.encode(reflect.of(o));
    same(s, "{\"id\":7,\"sizes\":[5,6],\"tags\":[\"red\",\"big\"],"
            "\"items\":[{\"name\":\"hammer\",\"kind\":\"tool\",\"qty\":1},"
            "{\"name\":\"nail\",\"kind\":\"part\",\"qty\":40}],"
            "\"grid\":[[0,1,2],[3,4,5]],\"none\":[]}",
         "numbers, Strings, structs with enums, nested Vecs and an empty Vec, as arrays");
    s.free();

    /* a Vec at the top level, compact and indented */
    s = json.encode(reflect.of(o.sizes));
    same(s, "[5,6]", "a Vec at the top level");
    s.free();
    s = json.encode(reflect.of(o.sizes), 2);
    same(s, "[\n  5,\n  6\n]", "indented");
    s.free();
    s = json.encode(reflect.of(o.none));
    same(s, "[]", "an empty (zeroed) Vec at the top level");
    s.free();

    /* a Vec<u8> is a string, as a u8[] is */
    vec.Vec<u8> bytes = vec.new<u8>();
    bytes.push('o');
    bytes.push('k');
    s = json.encode(reflect.of(bytes));
    same(s, "\"ok\"", "a Vec<u8> as a string, like u8[]");
    s.free();
    bytes.free();

    /* a struct of your own with the same fields stays an object */
    i64 three[3] = {1, 2, 3};
    MyVec mine;
    mine.data = three;
    mine.len = 3;
    mine.cap = 3;
    Bad empty;
    libc.memset(&empty, 0, sizeof(Bad));
    s = json.encode(reflect.of(empty));
    same(s, "{\"items\":[]}", "an empty Vec of something JSON can't hold is still []");
    s.free();

    /* an item that can't be JSON: the error names it */
    Bad bad;
    bad.items = vec.new<Holder>();
    Holder h;
    h.n = 1;
    h.f = &twice;
    bad.items.push(h);
    bad.items.push(h);
    bool threw = false;
    try {
        s = json.encode(reflect.of(bad));
        s.free();
    } catch (Error e) {
        threw = e.code == json.ERR_ENCODE && libc.strstr(e.message, "items[0].f:") != null;
        if (!threw) { printf("\n    error: %s\n", e.message); }
    }
    check(threw, "an item that can't be JSON throws ERR_ENCODE naming items[0].f");
    bad.items.free();

    /* MyVec: an object (its data pointer is written as what it points to) */
    s = json.encode(reflect.of(mine));
    check(str.startsWith(s, "{\"data\":1,\"len\":3"), "a struct with data/len/cap fields of its own stays an object");
    if (!str.startsWith(s, "{\"data\":1,\"len\":3")) { printf("    got: %s\n", s); }
    s.free();

    for (auto it : o.items.items()) { it.name.free(); }
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
