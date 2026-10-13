import cstr, json, mem, reflect, str, test, vec;

/* json.Reader (v0.14.0): reads JSON front to back with no tree, the way
 * generated fromJson decoders do (beginObject, a nextKey loop that
 * switches on the key, read* for each value, skipValue for the rest).
 * Bad input throws json.ERR_DECODE with line:col and the path. Also
 * Writer.rawKey (a key that is escaped already) and Writer.uint. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

bool is(u8[] got, u8 *want) {
    return str.equals(got, cstr.view(want));
}

enum Status { pending, paid, shipped };

struct Address {
    String street;
    String city;
    i64 zip;
};

struct Line {
    String sku;
    i64 qty;
    f64 price;
    bool gift;
};

struct Order {
    i64 id;
    String customer;
    Status status;
    Address ship;
    Line[] lines;
    vec.Vec<String> tags;
    f64 total;
    bool express;
    u8 *note;
};

/* What a generated decoder does: match each key, read its value, skip
 * the keys it doesn't know */
void readAddress(json.Reader *r, Address *a) {
    r.beginObject();
    while (r.nextKey()) {
        u8[] k = r.key();
        if (is(k, "street"))    { r.readString(&a.street); }
        else if (is(k, "city")) { r.readString(&a.city); }
        else if (is(k, "zip"))  { a.zip = r.readInt(); }
        else { r.skipValue(); }
    }
}

void readLine(json.Reader *r, Line *l) {
    r.beginObject();
    while (r.nextKey()) {
        u8[] k = r.key();
        switch (k.len) {
            case 3:
                if (is(k, "sku")) { r.readString(&l.sku); }
                else if (is(k, "qty")) { l.qty = r.readInt(); }
                else { r.skipValue(); }
            case 4:
                if (is(k, "gift")) { l.gift = r.readBool(); } else { r.skipValue(); }
            case 5:
                if (is(k, "price")) { l.price = r.readFloat(); } else { r.skipValue(); }
            default:
                r.skipValue();
        }
    }
}

void readOrder(json.Reader *r, Order *o) {
    r.beginObject();
    while (r.nextKey()) {
        u8[] k = r.key();
        if (is(k, "id")) { o.id = r.readInt(); }
        else if (is(k, "customer")) { r.readString(&o.customer); }
        else if (is(k, "status")) {
            i64 v;
            if (!reflect.enumValue(reflect.typeOf(o.status), r.readStr(), &v)) {
                r.fail("Status has no member of that name");
            }
            o.status = (Status)v;
        }
        else if (is(k, "ship")) { readAddress(r, &o.ship); }
        else if (is(k, "lines")) {
            i64 n = 0;
            Line *items = null;
            r.beginArray();
            while (r.nextItem()) {
                items = mem.realloc(items, (n + 1) * sizeof(Line));
                mem.set(&items[n], 0, sizeof(Line));
                readLine(r, &items[n]);
                n++;
            }
            o.lines = items[0:n];
        }
        else if (is(k, "tags")) {
            r.beginArray();
            while (r.nextItem()) {
                String t;
                r.readString(&t);
                o.tags.push(t);
            }
        }
        else if (is(k, "total")) { o.total = r.readFloat(); }
        else if (is(k, "express")) { o.express = r.readBool(); }
        else if (is(k, "note")) { o.note = r.isNull() ? null : r.readBytes(); }
        else { r.skipValue(); }
    }
}

/* The ERR_DECODE message reading text as an Order throws, "" if none */
String orderError(u8 *text) {
    Order o;
    mem.set(&o, 0, sizeof(Order));
    json.Reader r = json.readerNew(cstr.view(text));
    defer r.free();
    String msg;
    try {
        readOrder(&r, &o);
        r.finish();
    } catch (Error e) {
        if (e.code == json.ERR_DECODE) { msg.append(cstr.view(e.message)); }
        else { msg.appendFormat("code %x: %s", e.code, e.message); }
    }
    json.free(reflect.of(o));
    return msg;
}

/* Reads any document with peek() and the read calls; its error */
void walk(json.Reader *r) {
    switch (r.peek()) {
        case json.OBJECT:
            r.beginObject();
            while (r.nextKey()) { walk(r); }
        case json.ARRAY:
            r.beginArray();
            while (r.nextItem()) { walk(r); }
        case json.STRING: r.readStr();
        case json.INT:    r.readInt();
        case json.FLOAT:  r.readFloat();
        case json.BOOL:   r.readBool();
        case json.NULL:   r.isNull();
    }
}

String walkError(u8[] text, i64 skip = 0) {
    json.Reader r = json.readerNew(text);
    defer r.free();
    String msg;
    try {
        if (skip) { r.skipValue(); } else { walk(&r); }
        r.finish();
    } catch (Error e) {
        msg.append(cstr.view(e.message));
    }
    return msg;
}

void errs(u8 *text, u8 *want) {
    for (i64 skip = 0; skip < 2; skip++) {
        String got = walkError(cstr.view(text), skip);
        bool ok = is(got, want);
        check(ok, text);
        if (!ok) { printf("    %s: got \"%s\", want \"%s\"\n", skip ? "skipValue" : "walk", got, want); }
        got.free();
    }
}

void orderErrs(u8 *text, u8 *want) {
    String got = orderError(text);
    bool ok = is(got, want);
    check(ok, text);
    if (!ok) { printf("    got \"%s\", want \"%s\"\n", got, want); }
    got.free();
}

i32 main() {
    printf("Test - json.Reader: ");

    /* the generated-decoder shape reads what json.encode writes */
    u8 *doc = "{\"id\":42,\"customer\":\"Ada \\\"A\\\" L\\u00f8velace\",\"status\":\"shipped\","
              "\"ship\":{\"street\":\"1 Road\",\"city\":\"London\",\"zip\":10115,\"extra\":[1,{\"x\":null}]},"
              "\"lines\":[{\"sku\":\"A-1\",\"qty\":2,\"price\":9.99,\"gift\":true},"
              "{\"sku\":\"B-2\",\"qty\":1,\"price\":1e3,\"gift\":false,\"why\":\"\\ud83d\\ude00\"}],"
              "\"tags\":[\"x\",\"\"],\"total\":1019.98,\"express\":false,\"note\":\"n\\u0000b\","
              "\"unknown\":{\"a\":[[],{}],\"b\":-0.5e-3}}";
    Order o;
    mem.set(&o, 0, sizeof(Order));
    json.Reader r = json.readerNew(cstr.view(doc));
    readOrder(&r, &o);
    r.finish();
    r.free();
    check(o.id == 42 && is(o.customer, "Ada \"A\" L\xC3\xB8velace") && o.status == Status.shipped,
          "id, an escaped string, an enum by name");
    check(is(o.ship.street, "1 Road") && is(o.ship.city, "London") && o.ship.zip == 10115,
          "a nested object, an unknown key in it skipped");
    check(o.lines.len == 2 && is(o.lines[0].sku, "A-1") && o.lines[0].qty == 2 &&
          o.lines[0].price == 9.99 && o.lines[0].gift && o.lines[1].price == 1000.0 && !o.lines[1].gift,
          "an array of objects");
    check(o.tags.len == 2 && is(o.tags.get(0), "x") && o.tags.get(1).len == 0, "a Vec of Strings, an empty one");
    check(o.total == 1019.98 && !o.express, "a float, a bool");
    check(o.note && o.note[0] == 'n' && o.note[1] == 0 && o.note[2] == 'b' && o.note[3] == 0,
          "readBytes keeps a \\u0000 and ends with a NUL");

    /* the same as json.decode<Order> gives */
    String again = json.encode(reflect.of(o));
    Order d = json.decode<Order>(again);
    String twice = json.encode(reflect.of(d));
    check(str.equals(again, twice), "json.decode reads the same document back");
    Order o2;
    mem.set(&o2, 0, sizeof(Order));
    r = json.readerNew(again);
    readOrder(&r, &o2);
    r.finish();
    r.free();
    String thrice = json.encode(reflect.of(o2));
    check(str.equals(again, thrice), "the Reader reads what json.encode writes");
    json.free(reflect.of(o));
    json.free(reflect.of(o2));
    json.free(reflect.of(d));
    again.free();
    twice.free();
    thrice.free();

    /* strings: a view of the text when there is nothing to decode */
    u8[] text = cstr.view("[\"plain\", \"\\t\\/\", \"caf\xC3\xA9\"]");
    r = json.readerNew(text);
    r.beginArray();
    r.nextItem();
    u8[] v = r.readStr();
    check(is(v, "plain") && v.ptr == text.ptr + 2, "readStr: a view of the text");
    r.nextItem();
    check(is(r.readStr(), "\t/"), "readStr: escapes decoded");
    r.nextItem();
    String keep = str.from("something longer than the value");
    u8 *buf = keep.ptr;
    r.readString(&keep);
    check(is(keep, "caf\xC3\xA9") && keep.ptr == buf, "readString replaces the bytes, keeps the buffer");
    keep.free();
    check(!r.nextItem(), "nextItem: false at ']'");
    r.finish();
    r.free();

    /* numbers */
    r = json.readerNew(cstr.view("[9223372036854775807, -9223372036854775808, 18446744073709551615, "
                                 "0, -0, 12345678901234567890, 0.1, 2e-3, -7, 1.5]"));
    r.beginArray();
    r.nextItem(); check(r.peek() == json.INT && r.readInt() == 0x7FFFFFFFFFFFFFFF, "readInt: i64 max");
    r.nextItem(); check(r.readInt() == (i64)0x8000000000000000, "readInt: i64 min");
    r.nextItem(); check(r.peek() == json.FLOAT && r.readUint() == 0xFFFFFFFFFFFFFFFF, "readUint: u64 max");
    r.nextItem(); check(r.readUint() == 0, "readUint: 0");
    r.nextItem(); check(r.readInt() == 0, "readInt: -0");
    r.nextItem(); check(r.readFloat() == 12345678901234567890.0, "readFloat: past i64");
    r.nextItem(); check(r.readFloat() == 0.1, "readFloat: 0.1");
    r.nextItem(); check(r.peek() == json.FLOAT && r.readFloat() == 0.002, "readFloat: an exponent");
    r.nextItem(); check(r.readFloat() == -7.0, "readFloat: an integer");
    r.nextItem(); check(r.readFloat() == 1.5, "readFloat: 1.5");
    check(!r.nextItem(), "the end of the numbers");
    r.free();

    /* null, peek and skipValue read nothing or exactly one value */
    r = json.readerNew(cstr.view(" { \"a\" : null , \"b\" : [ 1 , \"]\" , { \"}\" : [ ] } ] , \"c\" : true } "));
    r.beginObject();
    check(r.nextKey() && is(r.key(), "a") && r.peek() == json.NULL && r.isNull(), "isNull reads null");
    check(r.nextKey() && is(r.key(), "b") && !r.isNull(), "isNull reads nothing else");
    r.skipValue();
    check(r.nextKey() && is(r.key(), "c") && r.readBool(), "skipValue skipped the whole array");
    check(!r.nextKey(), "nextKey: false at '}'");
    r.finish();
    r.free();

    /* the input's errors, with line:col */
    errs("", "1:1: expected a value, got the end of the input");
    errs("  \n ", "2:2: expected a value, got the end of the input");
    errs("{\"a\":1,}", "1:8: a: expected a key, got '}'");
    errs("{\"a\":1 \"b\":2}", "1:8: a: expected ',' or '}', got a string");
    errs("{\"a\" 1}", "1:6: a: expected ':' after the key, got a number");
    errs("{1:2}", "1:2: expected a key or '}', got a number");
    errs("[1,]", "1:4: [1]: a ',' before ']'");
    errs("[1 2]", "1:4: [0]: expected ',' or ']', got a number");
    errs("[\n  1,\n  {\"k\": [true, tru]}\n]", "3:16: [1].k[1]: expected a value, got something that isn't JSON");
    errs("[01]", "1:3: [0]: a malformed number");
    errs("[1.]", "1:4: [0]: a malformed number");
    errs("[-]", "1:3: [0]: a malformed number");
    errs("[+1]", "1:2: [0]: expected a value, got something that isn't JSON");
    errs("[1x]", "1:3: [0]: a malformed number");
    errs("\"abc", "1:5: a string with no closing quote");
    errs("\"a\\", "1:4: a string with no closing quote");
    errs("\"a\nb\"", "1:3: a control character in a string (write \\n, \\t, \\u0001 ...)");
    errs("\"\\q\"", "1:2: an unknown escape in a string");
    errs("\"\\u12g4\"", "1:2: \\u not followed by 4 hex digits");
    errs("\"\\udc00\"", "1:2: a \\u escape with an unpaired surrogate");
    errs("\"\\ud800x\"", "1:2: a \\u escape with an unpaired surrogate");
    errs("\"\xC0\xAF\"", "1:1: a string that isn't UTF-8");
    errs("\"\\n\xFF\"", "1:1: a string that isn't UTF-8");
    errs("{} []", "1:4: expected the end of the input, got an array");
    errs("nul", "1:1: expected a value, got something that isn't JSON");

    /* nested too deep */
    String deep;
    for (i64 i = 0; i <= json.MAX_DEPTH; i++) { deep.appendByte('['); }
    String got = walkError(deep.ptr[0:deep.len]);
    check(str.contains(got, cstr.view("nested deeper than json.MAX_DEPTH")), "MAX_DEPTH");
    got.free();
    deep.free();

    /* the wrong kind of value, with the path */
    orderErrs("{\"id\": \"7\"}", "1:8: id: expected an integer, got a string");
    orderErrs("{\"id\": 1.5}", "1:8: id: expected an integer that fits an i64, got a number");
    orderErrs("{\"id\": 9223372036854775808}", "1:8: id: expected an integer that fits an i64, got a number");
    orderErrs("{\"ship\": {\"zip\": true}}", "1:18: ship.zip: expected an integer, got a bool");
    orderErrs("{\"ship\": []}", "1:10: ship: expected an object, got an array");
    orderErrs("{\"lines\": [{\"qty\": 1}, {\"price\": \"x\"}]}",
              "1:34: lines[1].price: expected a number, got a string");
    orderErrs("{\"lines\": [{\"gift\": null}]}", "1:21: lines[0].gift: expected a bool, got null");
    orderErrs("{\"tags\": [\"a\", 2]}", "1:16: tags[1]: expected a string, got a number");
    orderErrs("{\"status\": \"lost\"}", "1:18: status: Status has no member of that name");
    orderErrs("{\"customer\": \"\\\"q\\\"\", \"esc\\\"aped\": 1, \"id\": [}",
              "1:45: id: expected an integer, got an array");
    orderErrs("{\"x\\\"y\": {\"a\": [1, }]}}", "1:20: x\\\"y.a[1]: expected a value, got '}'");
    orderErrs("{\"id\": 1} {", "1:11: expected the end of the input, got an object");

    /* Writer.rawKey and Writer.uint */
    json.Writer w = json.writerNew();
    w.beginObject();
    w.rawKey("a\\\"b");  w.uint(0xFFFFFFFFFFFFFFFF);
    w.rawKey("c");       w.beginArray(); w.uint(0); w.uint(10); w.end();
    w.key("d");          w.int(-1);
    w.end();
    String out = w.take();
    check(is(out, "{\"a\\\"b\":18446744073709551615,\"c\":[0,10],\"d\":-1}"), "rawKey, uint");
    out.free();
    w.free();
    w = json.writerNew(2);
    w.beginObject();
    w.rawKey("x"); w.uint(1);
    w.rawKey("y"); w.beginObject(); w.rawKey("z"); w.bool(true); w.end();
    w.end();
    out = w.take();
    check(is(out, "{\n  \"x\": 1,\n  \"y\": {\n    \"z\": true\n  }\n}"), "rawKey with indent");
    out.free();
    w.free();
    bool threw = false;
    w = json.writerNew();
    w.beginObject();
    w.rawKey("k");
    try { w.rawKey("j"); } catch (Error e) { threw = e.code == json.ERR_WRITER; }
    check(threw, "rawKey after rawKey with no value throws");
    w.free();

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
