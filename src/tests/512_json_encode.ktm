import cstr, json, mem, reflect, str, test;

/* v0.4.0 R4: json.encode turns a value of any type into JSON by its
 * reflect description: numbers, bools, enums by name, String/u8[]/u8 *
 * and u8 arrays as strings, arrays and slices, nested structs and
 * pointers (null as null), private fields skipped, compact or indented.
 * A union, a function or a void * throws ERR_ENCODE with the path. */

enum Role { guest, admin };
struct Addr { u8[] city; i32 zip; };
struct User {
    i64 id;
    String name;
    Role role;
    bool on;
    f64 score;
    f32 small;
    u8 code[4];
    Addr home;
    Addr *work;
    i64[] tags;
    Addr list[2];
    u8 *note;
    i8 neg;
};
struct WithFn { i64 a; fn(i64) i64 cb; };
struct WithAny { void *p; };
union Both { i64 n; f64 x; };
struct WithUnion { Both b; };
struct Deep { i64 a; WithFn inner; };

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

bool encodes(reflect.Value v, u8[] want, i64 indent = 0) {
    String got = json.encode(v, indent);
    bool ok = got.equals(want);
    if (!ok) { printf("\n  got %s", got); }
    got.free();
    return ok;
}

/* encode(v) throws ERR_ENCODE with a message starting with `want` */
bool fails(reflect.Value v, u8[] want) {
    bool ok = false;
    try {
        String s = json.encode(v);
        s.free();
    } catch (Error e) {
        ok = e.code == json.ERR_ENCODE && cstr.view(e.message).startsWith(want);
        if (!ok) { printf("\n  got %s", e.message); }
    }
    return ok;
}

i32 main() {
    printf("Test - json.encode: ");
    User u;
    mem.set(&u, 0, sizeof(User));
    u.id = 7;
    u.name = str.from("Ada \"A\"\n");
    u.role = Role.admin;
    u.on = true;
    u.score = 9.5;
    u.small = 0.25;
    u.code[0] = 'O';
    u.code[1] = 'K';
    u.home.city = "Iasi";
    u.home.zip = 700000;
    i64 tags[3] = {1, 2, 3};
    u.tags = tags;
    u.list[1].city = "Cluj";
    u.note = "hi";
    u.neg = -5;
    check(encodes(reflect.of(u),
        "{\"id\":7,\"name\":\"Ada \\\"A\\\"\\n\",\"role\":\"admin\",\"on\":true,\"score\":9.5,"
        "\"small\":0.25,\"code\":\"OK\",\"home\":{\"city\":\"Iasi\",\"zip\":700000},\"work\":null,"
        "\"tags\":[1,2,3],\"list\":[{\"city\":\"\",\"zip\":0},{\"city\":\"Cluj\",\"zip\":0}],"
        "\"note\":\"hi\",\"neg\":-5}"), "a struct of every kind");
    Addr w = {"Oradea", 410000};
    u.work = &w;
    u.note = null;
    u.role = (Role)9;
    String full = json.encode(reflect.of(u));
    check(full.contains("\"work\":{\"city\":\"Oradea\",\"zip\":410000}") && full.contains("\"note\":null") &&
          full.contains("\"role\":9"), "a pointer, a null u8 *, an enum value with no name");
    full.free();
    check(encodes(reflect.of(w), "{\n  \"city\": \"Oradea\",\n  \"zip\": 410000\n}", 2), "indented");
    check(encodes(reflect.of(tags), "[1,2,3]") && encodes(reflect.of(u.id), "7"), "not a struct");
    u.name.free();

    WithFn f;
    mem.set(&f, 0, sizeof(WithFn));
    check(fails(reflect.of(f), "cb: a fn(i64) i64 can't be JSON"), "a function");
    WithAny a;
    a.p = null;
    check(fails(reflect.of(a), "p: a void * can't be JSON"), "a void *");
    WithUnion wu;
    wu.b.n = 1;
    check(fails(reflect.of(wu), "b: a Both can't be JSON"), "a union");
    Deep d;
    mem.set(&d, 0, sizeof(Deep));
    check(fails(reflect.of(d), "inner.cb: a fn(i64) i64 can't be JSON"), "the path");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
