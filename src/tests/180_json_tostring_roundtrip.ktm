import test;

import json, mem, str;

/* JsonToString printed an empty object or array as `{(nil)}` / `[(nil)]`
 * and wrote strings with %Q (DolDoc quoting: `$` doubled, `\v`, raw
 * control characters), so its output couldn't always be parsed back. */

i64 gCorrect = 0;

void expect(u8 *got, u8 *want) {
    if (!str.cmp(got, want)) { gCorrect++; }
    else { printf("\n  got  %s\n  want %s\n", got, want); }
}

/* Parse -> string -> parse -> string must give the same text both times */
void roundTrip(u8 *text, u8 *want) {
    json.Value *a = json.parse(text);
    if (!json.ok(a)) {
        printf("\n  '%s' does not parse\n", text);
        return;
    }
    u8 *s1 = json.toStr(a);
    expect(s1, want);
    json.Value *b = json.parse(s1);
    if (json.ok(b)) {
        u8 *s2 = json.toStr(b);
        expect(s2, s1);
        mem.free(s2);
    } else {
        printf("\n  output '%s' does not parse back\n", s1);
    }
    mem.free(s1);
    json.release(a);
    json.release(b);
}

i32 main() {
    i64 tests = 17;
    printf("Test - JsonToString output parses back: ");

    roundTrip("{\"a\": {}, \"b\": []}", "{\"a\": {},\"b\": []}");
    roundTrip("[[], {}, [[]], {\"x\": {}}]", "[[],{},[[]],{\"x\": {}}]");
    roundTrip("{\"s\": \"tab\\there\"}", "{\"s\": \"tab\\there\"}");
    roundTrip("{\"s\": \"\\b\\f\\n\\r\\t\"}", "{\"s\": \"\\b\\f\\n\\r\\t\"}");
    roundTrip("{\"s\": \"q\\\"b\\\\s\\/\"}", "{\"s\": \"q\\\"b\\\\s/\"}");
    roundTrip("{\"$k%\": \"$1 100% {}[]\"}", "{\"$k%\": \"$1 100% {}[]\"}");
    roundTrip("{\"k\\n\": 1}", "{\"k\\n\": 1}");

    /* Other control characters become \u00XX; DEL and UTF-8 stay as they are */
    json.Value *j = json.parse("{\"s\": \"x\"}");
    mem.free(j.object.str);
    u8 *ctl = str.dup("a\x01b\x1Fc\x0B\x7F\xC3\xA9");
    j.object.str = ctl;
    j.object.strLen = str.len(ctl); /* the length JsonToString writes */
    u8 *out = json.toStr(j);
    expect(out, "{\"s\": \"a\\u0001b\\u001fc\\u000b\x7F\xC3\xA9\"}");
    mem.free(out);
    json.release(j);

    /* A top-level object or array that has become empty */
    j = json.parse("{\"a\": 1}");
    json.release(j.object);
    j.object = null;
    out = json.toStr(j);
    expect(out, "{}");
    mem.free(out);
    json.release(j);
    j = json.parse("[1]");
    json.release(j.array);
    j.array = null;
    out = json.toStr(j);
    expect(out, "[]");
    mem.free(out);
    json.release(j);

    test.printResult(gCorrect, tests);
    printf("====\n");
    return 0;
}
