import json, mem, reflect, str, test, yaml;

/* @json and @yaml fields that point to a number, bool, enum or String
 * (v0.20.0): null when the pointer is, the value otherwise; decoding
 * allocates it, freeDecoded frees it. Before: "a i64 * can't be JSON".
 * And `void *` converts to an enum pointer without a warning. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

enum Color { red, blue };

@json @yaml struct Row {
    i64 id;
    i64 *age;
    f64 *score;
    bool *active;
    Color *color;
    String *nick;
    i32 *small;
};

i32 main() {
    printf("Test - @json pointers to values: ");
    Row r;
    r.id = 1;
    String j = json.encode(reflect.of(r));
    check(str.equals(j[0:], "{\"id\":1,\"age\":null,\"score\":null,\"active\":null,\"color\":null,\"nick\":null,\"small\":null}"),
          "null pointers are null");
    j.free();
    Row d = json.decode<Row>("{\"id\":2,\"age\":41,\"score\":1.5,\"active\":true,\"color\":\"blue\",\"nick\":\"ada\",\"small\":-3}");
    check(*d.age == 41 && *d.score == 1.5 && *d.active && *d.color == Color.blue &&
          str.equals((*d.nick)[0:], "ada") && *d.small == -3, "decoded values");
    j = json.encode(reflect.of(d));
    check(str.equals(j[0:], "{\"id\":2,\"age\":41,\"score\":1.5,\"active\":true,\"color\":\"blue\",\"nick\":\"ada\",\"small\":-3}"),
          "encoded back");
    j.free();
    d.freeDecoded();
    check(d.age == null && d.nick == null, "freeDecoded frees and clears them");
    Row n = json.decode<Row>("{\"id\":3,\"age\":null,\"nick\":null}");
    check(n.id == 3 && n.age == null && n.nick == null, "null decodes to a null pointer");
    n.freeDecoded();
    yaml.Reader yr = yaml.readerNew("id: 4\nage: 7\nnick: bo\n");
    Row y;
    y.fromYaml(&yr);
    yr.free();
    check(*y.age == 7 && str.equals((*y.nick)[0:], "bo") && y.score == null, "yaml too");
    y.freeDecoded();
    Color *c = mem.alloc(sizeof(Color));
    *c = Color.red;
    check(*c == Color.red, "void * to an enum pointer");
    mem.free(c);
    c = null;
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
