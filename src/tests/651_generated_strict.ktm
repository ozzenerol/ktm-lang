import cstr, json, libc, mem, reflect, str, test, vec, yaml;

/* json.STRICT and yaml.STRICT with @json/@yaml (v0.14.0): the generated
 * fromJson/fromYaml throw on a key the struct has no field for and on a
 * missing key, as json.decode's reflection path does ("ship.city:
 * missing", "extra: no such field in Order"), with the position in front.
 * @skip and private fields are never required. Turned on with
 * json.readerNew(text, json.STRICT) and yaml.readerNew(text, yaml.STRICT). */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

@json @yaml
struct Ship {
    String city;
    i64 zip;
};

@json @yaml
struct Item {
    String sku;
    i64 n;
};

@json @yaml
struct Order {
    @key("ID") i64 id;
    Ship ship;
    vec.Vec<Item> items;
    @skip i64 cache;
};

/* The same fields without @json: json.decode reads it by reflection */
struct PlainShip {
    String city;
    i64 zip;
};

struct PlainItem {
    String sku;
    i64 n;
};

struct PlainOrder {
    i64 ID;
    PlainShip ship;
    vec.Vec<PlainItem> items;
};

/* The ERR_DECODE message of fromJson on text, a Reader with `flags`
 * ("" if none) */
String jsonError(u8 *text, i64 flags) {
    String msg;
    Order o;
    mem.set(&o, 0, sizeof(Order));
    json.Reader r = json.readerNew(cstr.view(text), flags);
    try {
        o.fromJson(&r);
        r.finish();
    } catch (Error e) {
        if (e.code == json.ERR_DECODE) { msg.append(cstr.view(e.message)); }
        else { msg.appendFormat("code %x", e.code); }
    }
    r.free();
    o.freeDecoded();
    return msg;
}

/* The same through reflection, with PlainOrder */
String plainError(u8 *text) {
    String msg;
    PlainOrder o;
    mem.set(&o, 0, sizeof(PlainOrder));
    try {
        json.decode(cstr.view(text), reflect.of(o), json.STRICT);
    } catch (Error e) {
        msg.append(cstr.view(e.message));
    }
    json.free(reflect.of(o));
    return msg;
}

String yamlError(u8 *text, i64 flags) {
    String msg;
    Order o;
    mem.set(&o, 0, sizeof(Order));
    yaml.Reader r = yaml.readerNew(cstr.view(text), flags);
    try {
        o.fromYaml(&r);
        r.finish();
    } catch (Error e) {
        if (e.code == yaml.ERR_DECODE) { msg.append(cstr.view(e.message)); }
        else { msg.appendFormat("code %x", e.code); }
    }
    r.free();
    o.freeDecoded();
    return msg;
}

void is(String got, u8 *want, u8 *what) {
    bool ok = str.equals(got, cstr.view(want));
    check(ok, what);
    if (!ok) { printf("    got \"%s\", want \"%s\"\n", got, want); }
    got.free();
}

/* got is what reflection says, which starts with "line:col: " too (decode
 * reads with a json.Reader) */
void likeReflection(u8 *text, u8 *pos) {
    String got = jsonError(text, json.STRICT);
    String want = plainError(text);
    bool ok = want.len > libc.strlen(pos) && str.startsWith(want, cstr.view(pos)) && str.equals(got, want);
    check(ok, text);
    if (!ok) { printf("    got \"%s\", want \"%s\"\n", got, want); }
    got.free();
    want.free();
}

#define FULL "{\"ID\":1,\"ship\":{\"city\":\"Oslo\",\"zip\":150},\"items\":[{\"sku\":\"a\",\"n\":2}]}"

i32 main() {
    printf("Test - STRICT in generated decoders: ");

    /* every key there: fine either way */
    is(jsonError(FULL, json.STRICT), "", "STRICT, nothing missing");
    is(jsonError(FULL, 0), "", "no flags");
    Order o;
    mem.set(&o, 0, sizeof(Order));
    json.Reader r = json.readerNew(FULL, json.STRICT);
    o.fromJson(&r);
    r.finish();
    r.free();
    check(o.id == 1 && o.ship.zip == 150 && o.items.len == 1 && o.items.get(0).n == 2,
          "STRICT reads the document");
    o.freeDecoded();

    /* without STRICT, missing and unknown keys are fine */
    is(jsonError("{\"zzz\":[1],\"ship\":{\"q\":null}}", 0), "", "no flags: unknown and missing keys");

    /* with it, the errors reflection gives, the position in front */
    likeReflection("{\"ID\":1,\"ship\":{\"city\":\"Oslo\",\"zip\":150}}", "1:42: ");
    likeReflection("{\"ID\":1,\"ship\":{\"zip\":150},\"items\":[]}", "1:27: ");
    likeReflection("{\"ID\":1,\"ship\":{\"city\":\"x\",\"zip\":1},\"items\":[{\"sku\":\"a\",\"n\":1},{\"sku\":\"b\"}]}",
                   "1:75: ");
    is(jsonError("{\"ID\":1,\"extra\":2}", json.STRICT), "1:17: extra: no such field in Order",
       "a key with no field");
    is(jsonError("{\"ID\":1,\"ship\":{\"city\":\"x\",\"zip\":1,\"zzz\":0}}", json.STRICT),
       "1:42: ship.zzz: no such field in Ship", "a nested key with no field");
    /* @key names the key, @skip is never required (nor read) */
    is(jsonError("{\"ship\":{\"city\":\"x\",\"zip\":1},\"items\":[]}", json.STRICT),
       "1:41: ID: missing", "the @key name is the one missing");
    is(jsonError("{\"ID\":1,\"ship\":{\"city\":\"x\",\"zip\":1},\"items\":[],\"cache\":5}", json.STRICT),
       "1:56: cache: no such field in Order", "a @skip field has no key");

    /* yaml.STRICT */
    is(yamlError("ID: 1\nship:\n  city: Oslo\n  zip: 150\nitems: []\n", yaml.STRICT), "", "yaml STRICT, nothing missing");
    is(yamlError("ID: 1\nzzz: 2\n", 0), "", "yaml without STRICT");
    is(yamlError("ID: 1\nship:\n  city: Oslo\nitems: []\n", yaml.STRICT), "4:1: ship.zip: missing",
       "yaml: a nested key missing");
    is(yamlError("ID: 1\nzzz: 2\n", yaml.STRICT), "2:6: zzz: no such field in Order", "yaml: a key with no field");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
