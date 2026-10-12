import cstr, json, str, test;

/* json.Writer (v0.4.0): builds a document into a String with the commas,
 * quotes and escapes written for it; misuse and values JSON can't hold
 * throw json.ERR_WRITER, and what it writes parses back with json.parse. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* The ERR_WRITER message a call throws, "" when it doesn't */
enum Call { valueTwice, valueNoKey, keyInArray, keyAtTop, endNone, endAfterKey, keyTwice,
            takeOpen, takeEmpty, nan, inf, badUtf8, badKey, badRaw, rawTwo, tooDeep, badCstr };

bool throws(Call c, u8 *want) {
    json.Writer w = json.writerNew();
    defer w.free();
    try {
        switch (c) {
            case Call.valueTwice:  w.int(1); w.int(2);
            case Call.valueNoKey:  w.beginObject(); w.str("x");
            case Call.keyInArray:  w.beginArray(); w.key("k");
            case Call.keyAtTop:    w.key("k");
            case Call.endNone:     w.beginArray(); w.end(); w.end();
            case Call.endAfterKey: w.beginObject(); w.key("k"); w.end();
            case Call.keyTwice:    w.beginObject(); w.key("a"); w.key("b");
            case Call.takeOpen:    w.beginArray(); w.beginObject(); w.take();
            case Call.takeEmpty:   w.take();
            case Call.nan:         w.float(0.0 / 0.0);
            case Call.inf:         w.beginArray(); w.float(-1.0 / 0.0);
            case Call.badUtf8:     w.str("ok\xC0\xAF");
            case Call.badKey:      w.beginObject(); w.key("\xED\xA0\x80");
            case Call.badRaw:      w.raw("{\"a\": }");
            case Call.rawTwo:      w.raw("1 2");
            case Call.badCstr:     w.cstr("\xFF");
            case Call.tooDeep:
                for (i64 i = 0; i <= json.MAX_DEPTH; i++) { w.beginArray(); }
        }
    } catch (Error e) {
        bool ok = e.code == json.ERR_WRITER && str.contains(cstr.view(e.message), cstr.view(want));
        if (!ok) { printf("\n  code %x, message \"%s\"", e.code, e.message); }
        return ok;
    }
    return false;
}

i32 main() {
    printf("Test - json.Writer: ");

    /* The brief's document, compact */
    json.Writer w = json.writerNew();
    defer w.free();
    w.beginObject();
    w.key("id");    w.int(5);
    w.key("name");  w.str("Ada \"the\" first");
    w.key("tags");  w.beginArray(); w.str("a"); w.float(1.5); w.bool(true); w.nil(); w.end();
    w.key("none");  w.beginObject(); w.end();
    w.key("empty"); w.beginArray(); w.end();
    w.end();
    String text = w.take();
    check(text.equals("{\"id\":5,\"name\":\"Ada \\\"the\\\" first\",\"tags\":[\"a\",1.5,true,null],"
                      "\"none\":{},\"empty\":[]}"), "compact text");
    check(w.out.len == 0 && w.out.ptr == null && w.depth == 0, "take() leaves the Writer empty");

    /* Round trip: parse the text back and check every value */
    json.Value *doc = json.parse(text);
    check(json.ok(doc), "the text parses");
    if (json.ok(doc)) {   /* the checks below need the tree */
        check(json.select(doc, ".id:i").intVal == 5, "int back");
        check(json.select(doc, ".name:s").str.equals("Ada \"the\" first"), "string back");
        check(json.len(json.select(doc, ".tags:a")) == 4 && json.select(doc, ".tags[1]:f").floatVal == 1.5 &&
              json.select(doc, ".tags[2]:b").boolean && json.select(doc, ".tags[3]:!"), "array back");
        check(json.len(json.select(doc, ".none:o")) == 0 && json.len(json.select(doc, ".empty:a")) == 0,
              "empty object and array back");
    }
    json.release(doc);
    text.free();

    /* The same Writer again: strings with escapes, NULs and UTF-8; keys of
     * any bytes; String and sub-slice arguments; extreme numbers */
    String owned = str.from("from a String");
    defer owned.free();
    u8[] whole = "xx-slice-xx";
    u8[] odd = "q\"\\/\b\f\n\r\t\x01\x1F\x7F\0end \xC3\xA9 \xE2\x82\xAC \xF0\x9F\x98\x80";
    f64 floats[6] = {0.1, -2.0, 1e300, 5e-324, 1.0 / 3.0, 123456789012345680.0};
    w.beginArray();
    w.str(odd);
    w.str(owned);
    w.str(whole[3:8]);
    w.str("");
    w.int(I64_MIN); w.int(I64_MAX); w.int(0);
    for (i64 i = 0; i < 6; i++) { w.float(floats[i]); }
    w.beginObject(); w.key("k\0\"ey"); w.bool(false); w.key(""); w.nil(); w.end();
    w.end();
    text = w.take();
    check(text.startsWith("[\"q\\\"\\\\/\\b\\f\\n\\r\\t\\u0001\\u001f\x7F\\u0000end \xC3\xA9"),
          "escapes as json.toStr writes them, UTF-8 kept");
    doc = json.parse(text);
    check(json.ok(doc), "the second text parses");
    if (json.ok(doc)) {   /* the checks below need the tree */
        check(json.arrayGet(doc, 0).str.equals(odd), "odd string round trip");
        check(json.arrayGet(doc, 1).str.equals("from a String") && json.arrayGet(doc, 2).str.equals("slice") &&
              json.arrayGet(doc, 3).str.len == 0, "String, sub-slice and empty arguments");
        check(json.arrayGet(doc, 4).intVal == I64_MIN && json.arrayGet(doc, 5).intVal == I64_MAX &&
              json.arrayGet(doc, 6).type == json.INT, "ints back");
        bool floatsOk = true;
        for (i64 i = 0; i < 6; i++) {
            json.Value *f = json.arrayGet(doc, 7 + i);
            floatsOk = floatsOk && f.type == json.FLOAT && f.floatVal == floats[i];
        }
        check(floatsOk, "floats read back exactly, as floats");
        json.Value *obj = json.arrayGet(doc, 13);
        check(json.objectGet(obj, "k\0\"ey").type == json.BOOL && json.objectGet(obj, "").type == json.NULL,
              "keys with escapes back");
        /* The Writer and json.toStr agree on strings and floats */
        String viaToStr = json.toStr(json.arrayGet(doc, 0));
        check(text.contains(viaToStr), "str() writes what toStr writes");
        viaToStr.free();
        viaToStr = json.toStr(json.arrayGet(doc, 11));
        check(text.contains(viaToStr), "float() writes what toStr writes");
        viaToStr.free();
    }
    json.release(doc);
    text.free();

    /* Top-level values other than arrays and objects; raw() */
    w.str("only"); text = w.take();
    check(text.equals("\"only\""), "a string document");
    text.free();
    w.beginObject(); w.key("pre"); w.raw(" [1, {\"x\": 2}] "); w.key("n"); w.int(-3); w.end();
    text = w.take();
    check(text.equals("{\"pre\": [1, {\"x\": 2}] ,\"n\":-3}"), "raw() inserts the text as it is");
    text.free();

    /* Indented */
    json.Writer p = json.writerNew(2);
    defer p.free();
    p.beginObject();
    p.key("a"); p.beginArray(); p.int(1); p.beginObject(); p.key("b"); p.nil(); p.end(); p.end();
    p.key("e"); p.beginArray(); p.end();
    p.end();
    text = p.take();
    check(text.equals("{\n  \"a\": [\n    1,\n    {\n      \"b\": null\n    }\n  ],\n  \"e\": []\n}"),
          "indented text");
    doc = json.parse(text);
    check(json.ok(doc) && json.select(doc, ".a[1].b:!") != null, "indented text parses");
    json.release(doc);
    text.free();

    /* Misuse and values JSON can't hold throw json.ERR_WRITER */
    check(throws(Call.valueTwice, "already has its top-level value"), "a second top-level value");
    check(throws(Call.valueNoKey, "needs key() first"), "a value where a key is expected");
    check(throws(Call.keyInArray, "no object is open"), "key() in an array");
    check(throws(Call.keyAtTop, "no object is open"), "key() at the top");
    check(throws(Call.endNone, "no array or object is open"), "end() with nothing open");
    check(throws(Call.endAfterKey, "has no value"), "end() after a key");
    check(throws(Call.keyTwice, "has no value yet"), "key() after key()");
    check(throws(Call.takeOpen, "2 array(s) or object(s) still open"), "take() with something open");
    check(throws(Call.takeEmpty, "nothing was written"), "take() with nothing written");
    check(throws(Call.nan, "no NaN or infinity"), "NaN");
    check(throws(Call.inf, "no NaN or infinity"), "infinity");
    check(throws(Call.badUtf8, "isn't UTF-8"), "str() of an overlong sequence");
    check(throws(Call.badKey, "isn't UTF-8"), "key() of a surrogate");
    check(throws(Call.badRaw, "isn't one JSON value"), "raw() of bad JSON");
    check(throws(Call.rawTwo, "isn't one JSON value"), "raw() of two values");
    check(throws(Call.tooDeep, "MAX_DEPTH"), "deeper than json.MAX_DEPTH");

    /* cstr(): a C string, null as null */
    u8 *cs = "c \"str\"", *none = null;
    w.beginArray(); w.cstr(cs); w.cstr(none); w.end();
    text = w.take();
    check(text.equals("[\"c \\\"str\\\"\",null]"), "cstr() of a C string and of null");
    text.free();
    check(throws(Call.badCstr, "isn't UTF-8"), "cstr() of bad UTF-8");

    /* A throw leaves the Writer as it was: the document can be finished */
    w.beginObject();
    w.key("a");
    try { w.str("\xFF"); } catch {}
    try { w.end(); } catch {}
    w.int(1);
    try { w.int(2); } catch {}
    w.end();
    text = w.take();
    check(text.equals("{\"a\":1}"), "a throw changes nothing");
    text.free();

    /* MAX_DEPTH levels are fine (json.parse takes them too) */
    for (i64 i = 0; i < json.MAX_DEPTH; i++) { w.beginArray(); }
    for (i64 i = 0; i < json.MAX_DEPTH; i++) { w.end(); }
    text = w.take();
    doc = json.parse(text);
    check(text.len == 2 * json.MAX_DEPTH && json.ok(doc), "json.MAX_DEPTH levels");
    json.release(doc);
    text.free();

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
