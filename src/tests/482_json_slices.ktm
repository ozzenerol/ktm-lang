import cstr, json, test;

/* json with String and u8[] (v0.3.0 step 6c): json.parse takes the text
 * as a u8[] (a part of one is a sub-slice; the only option left is the
 * flags), json.toStr and json.errorStr give Strings, a Value's str and
 * key are u8[] views it owns (with their \u0000s), and json.objectGet
 * takes a u8[] key, matched on all its bytes. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 main() {
    printf("Test - json with String and u8[]: ");
    u8[] text = "{\"name\": \"ktm\", \"k\\u0000ey\": \"a\\u0000b\", \"\": 4} trailing";
    json.Value *bad = json.parse(text);
    check(!json.ok(bad), "trailing content is an error");
    String msg = json.errorStr(bad);
    check(msg.ptr && msg.contains("line 1"), "errorStr is a String");
    msg.free();
    json.release(bad);

    json.Value *doc = json.parse(text[0:text.find(" trailing")]);
    check(json.ok(doc), "a sub-slice of the text");
    String none = json.errorStr(doc);
    check(none.ptr == null, "no error: no ptr");
    json.Value *name = json.select(doc, ".name:s");
    check(name && name.str.equals("ktm") && !cstr.cmp(name.str.ptr, "ktm"), "str is a u8[] with a NUL after it");
    json.Value *nul = json.objectGet(doc, "k\0ey");
    check(nul && nul.str.len == 3 && nul.str.equals("a\0b") && nul.key.len == 4, "\\u0000 in str and key");
    check(json.objectGet(doc, "k") == null && json.objectGet(doc, "").intVal == 4, "keys match on all their bytes");

    String out = json.toStr(doc);
    defer out.free();
    check(!cstr.cmp(typeof(out), "String") && out.startsWith("{\"name\": \"ktm\"") &&
          out.contains("\"k\\u0000ey\": \"a\\u0000b\""), "toStr is a String");
    json.Value *again = json.parse(out);
    String out2 = json.toStr(again);
    defer out2.free();
    check(out2.equals(out), "a String parses back the same");
    json.release(again);
    json.release(doc);

    json.Value *num = json.parse("[12345678901234567890]", json.STRNUM_FLAG);
    check(json.ok(num) && !cstr.cmp(num.array.strnum, "12345678901234567890"), "flags still work");
    json.release(num);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
