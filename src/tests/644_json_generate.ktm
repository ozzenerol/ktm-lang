import cstr, fs, json, libc, mem, proc, str, test, vec;

/* @json (v0.14.0): the compiler writes S.toJson(json.Writer *),
 * S.fromJson(json.Reader *) and S.freeDecoded() for the struct, with no
 * reflection at run time. Every field kind the reflection encoder takes,
 * @key / @skip / @omitEmpty, unknown keys skipped, a struct declared after
 * the one using it, a generic struct's instance; decode errors say where;
 * a field that can't be JSON is a compile error at the field. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

bool same(u8[] a, u8 *b) { return a.len == libc.strlen(b) && libc.memcmp(a.ptr, b, a.len) == 0; }

enum Kind { tool, part, spare };

struct Note;

@json
struct Line {
    String sku;
    @omitEmpty i64 qty;
    Kind kind;
    Note *note;               /* declared below */
};

@json
struct Note {
    u8[] text;
};

@json
struct Box<T> {
    T value;
    @key("n") i64 count;
};

@json
struct Order {
    i64 id;
    @key("line-items") Line[] lines;
    vec.Vec<i64> sizes;
    vec.Vec<String> tags;
    f64 total;
    bool paid;
    u8 *memo;
    Line *first;
    @skip i64 hits;
    i32 small;
    u8 code;
    u64 big;
    i64 grid[3];
    u8 cc[8];
    Box<i64> box;
};

String encode(Order *o) {
    json.Writer w = json.writerNew();
    o.toJson(&w);
    return w.take();
}

/* decoding `text` into an Order throws a message with `want` */
bool decodeFails(u8 *text, u8 *want) {
    Order o;
    mem.set(&o, 0, sizeof(Order));
    bool ok = false;
    try {
        json.Reader r = json.readerNew(cstr.view(text));
        o.fromJson(&r);
    } catch (Error e) {
        ok = e.code == json.ERR_DECODE && libc.strstr(e.message, want) != null;
        if (!ok) { printf("\n    error: %s\n", e.message); }
    }
    o.freeDecoded();
    return ok;
}

#define GEN_FILE "__644_json_generate.ktm"
#define GEN_OUT  "__644_json_generate.out"

bool compileFails(u8 *source, u8 *msg) {
    fs.remove(GEN_FILE);
    if (!fs.writeAll(GEN_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    i64 res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " GEN_FILE " > " GEN_OUT " 2>&1");
    u8 *out = fs.readAll(GEN_OUT).take();
    bool ok = out && res == 1 && libc.strstr(out, msg) != null;
    if (!ok) { printf("\n    status %d\n%s\n", res, out); }
    mem.free(out);
    fs.remove(GEN_FILE);
    fs.remove(GEN_OUT);
    fs.remove("./a.out");
    return ok;
}

i32 main() {
    printf("Test - @json generated methods: ");

    Note n;
    n.text = "hello";
    Line l;
    mem.set(&l, 0, sizeof(Line));
    l.sku = str.from("A1");
    l.kind = Kind.part;
    l.note = &n;
    Order o;
    mem.set(&o, 0, sizeof(Order));
    o.id = 7;
    o.lines = (&l)[0:1];
    o.sizes.push(1);
    o.sizes.push(2);
    o.tags.push(str.from("x"));
    o.total = 2.5;
    o.paid = true;
    o.memo = "m";
    o.first = &l;
    o.hits = 99;
    o.small = -3;
    o.code = 200;
    o.big = 18446744073709551615;
    o.grid[0] = 1; o.grid[1] = 2; o.grid[2] = 3;
    libc.strcpy(&o.cc[0], "abc");
    o.box.value = 5;
    o.box.count = 6;

    String text = encode(&o);
    u8 *want = "{\"id\":7,\"line-items\":[{\"sku\":\"A1\",\"kind\":\"part\",\"note\":{\"text\":\"hello\"}}],"
               "\"sizes\":[1,2],\"tags\":[\"x\"],\"total\":2.5,\"paid\":true,\"memo\":\"m\","
               "\"first\":{\"sku\":\"A1\",\"kind\":\"part\",\"note\":{\"text\":\"hello\"}},"
               "\"small\":-3,\"code\":200,\"big\":18446744073709551615,\"grid\":[1,2,3],\"cc\":\"abc\","
               "\"box\":{\"value\":5,\"n\":6}}";
    bool encoded = same(text, want);
    check(encoded, "toJson writes every field, @key / @skip / @omitEmpty as marked");
    if (!encoded) { printf("    got:  %s\n    want: %s\n", text, want); }

    Order b;
    mem.set(&b, 0, sizeof(Order));
    json.Reader r = json.readerNew(text);
    b.fromJson(&r);
    r.finish();
    check(b.id == 7 && b.small == -3 && b.code == 200 && b.big == 18446744073709551615, "ints back");
    check(b.total == 2.5 && b.paid, "float and bool back");
    check(b.lines.len == 1 && same(b.lines[0].sku, "A1") && b.lines[0].kind == Kind.part,
          "a slice of structs, a String, an enum by name");
    check(b.lines[0].note && same(b.lines[0].note.text, "hello"), "a pointer to a struct declared later");
    check(b.sizes.len == 2 && b.sizes.data[1] == 2 && b.tags.len == 1 && same(b.tags.data[0], "x"),
          "vec.Vecs back");
    check(libc.strcmp(b.memo, "m") == 0 && b.first && same(b.first.sku, "A1"), "u8 * and a struct pointer");
    check(b.grid[2] == 3 && libc.strcmp(&b.cc[0], "abc") == 0, "arrays back");
    check(b.box.value == 5 && b.box.count == 6, "a generic struct's instance");
    check(b.hits == 0, "a @skip field isn't read");
    String again = encode(&b);
    check(str.equals(again, text), "what it reads writes the same text");
    again.free();
    b.freeDecoded();
    check(b.lines.len == 0 && b.memo == null && b.first == null && b.sizes.len == 0, "freeDecoded frees and clears");

    Order c;
    mem.set(&c, 0, sizeof(Order));
    json.Reader r2 = json.readerNew("{\"zzz\":[1,{\"a\":null}],\"id\":3,\"line-items\":[{\"sku\":\"q\",\"kind\":2,\"qty\":4}],\"first\":null}");
    c.fromJson(&r2);
    check(c.id == 3 && c.lines[0].kind == Kind.spare && c.lines[0].qty == 4 && c.first == null,
          "unknown keys skipped, an enum by number, null");
    c.freeDecoded();

    check(decodeFails("{\"line-items\":[{\"kind\":\"nope\"}]}", "not a member of Kind"), "an enum name that isn't one");
    check(decodeFails("{\"small\":3000000000}", "out of range for a i32"), "an int out of range");
    check(decodeFails("{\"id\":\"x\"}", "id: expected an integer"), "a wrong type");
    check(decodeFails("{\"grid\":[1,2,3,4]}", "more than 3 items"), "too many array items");
    check(decodeFails("{\"cc\":\"abcdefghij\"}", "too long for its array"), "a C string too long");

    check(compileFails("struct L { i64 a; };\n@json struct U { L l; };\ni32 main() { return 0; }\n",
                       "needs `import json;` in its file"), "@json without import json");
    check(compileFails("import json;\nstruct L { i64 a; };\n@json struct U { L l; };\ni32 main() { return 0; }\n",
                       "field `l` of @json U: it is a L, which has no @json"), "a struct field without @json");
    check(compileFails("import json;\n@json struct U { fn(i64) i64 f; };\ni32 main() { return 0; }\n",
                       "can't be JSON (mark it @skip"), "a field that can't be JSON");
    check(compileFails("import json;\nstruct N;\n@json struct U { i64 a; N *n; };\nstruct N { i64 b; };\ni32 main() { U u; return 0; }\n",
                       "field `n` of @json U: it points to a N, which has no @json"),
          "a pointer to a struct declared later without @json: an error at the field");

    text.free();
    l.sku.free();
    o.sizes.free();
    o.tags.data[0].free();
    o.tags.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
