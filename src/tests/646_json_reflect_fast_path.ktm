import json, libc, mem, reflect, str, test;

/* json.encode, json.decode and json.free on a @json struct call its
 * generated methods (v0.14.0): reflect.Type.toJson / fromJson /
 * freeDecoded, also for a @json struct inside one without @json, and
 * with json.STRICT (the generated fromJson checks the keys, test 651). */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

bool same(u8[] a, u8 *b) { return a.len == libc.strlen(b) && libc.memcmp(a.ptr, b, a.len) == 0; }

@json
struct Line {
    String sku;
    @key("n") i64 qty;
    @skip i64 hits;
};

struct Plain {
    i64 id;
    Line line;
};

i32 main() {
    printf("Test - json fast path for @json: ");

    reflect.Type *t = reflect.typeOf(Line);
    check(t.toJson && t.fromJson && t.freeDecoded, "reflect.Type has the generated methods");
    check(!reflect.typeOf(Plain).toJson, "not without @json");

    Plain p;
    mem.set(&p, 0, sizeof(Plain));
    p.id = 1;
    p.line.sku = str.from("A");
    p.line.qty = 2;
    p.line.hits = 5;
    String s = json.encode(reflect.of(p));
    check(same(s, "{\"id\":1,\"line\":{\"sku\":\"A\",\"n\":2}}"),
          "encode uses toJson for a @json struct inside a plain one (@key, @skip)");
    s.free();
    p.line.sku.free();

    Line l = json.decode<Line>("{\"sku\":\"B\",\"n\":4,\"other\":true}");
    check(same(l.sku, "B") && l.qty == 4, "decode<T> uses fromJson");
    json.free(reflect.of(l));
    check(l.sku.len == 0, "free uses freeDecoded");

    bool threw = false;
    try {
        Line bad = json.decode<Line>("{\"n\":\"x\"}");
    } catch (Error e) {
        threw = e.code == json.ERR_DECODE && libc.strstr(e.message, "1:6: n: expected an integer") != null;
        if (!threw) { printf("\n    error: %s\n", e.message); }
    }
    check(threw, "a decode error from the Reader says where");

    /* STRICT: a missing key is an error */
    bool strict = false;
    Line m;
    mem.set(&m, 0, sizeof(Line));
    try {
        json.decode("{\"sku\":\"C\"}", reflect.of(m), json.STRICT);
    } catch (Error e) {
        strict = e.code == json.ERR_DECODE;
    }
    json.free(reflect.of(m));
    check(strict, "STRICT: a missing key is an error");
    bool ok = true;
    try {
        json.decode("{\"sku\":\"C\",\"n\":1}", reflect.of(m), json.STRICT);
    } catch (Error e) {
        ok = false;
        printf("\n    error: %s\n", e.message);
    }
    check(ok && m.qty == 1 && same(m.sku, "C"), "STRICT reads @key and leaves @skip out");
    json.free(reflect.of(m));

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
